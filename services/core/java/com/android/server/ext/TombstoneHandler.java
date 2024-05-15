package com.android.server.ext;

import android.annotation.CurrentTimeMillisLong;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManagerInternal;
import android.ext.ErrorReportUi;
import android.ext.SettingsIntents;
import android.ext.settings.ExtSettings;
import android.os.DropBoxManager;
import android.os.Process;
import android.os.TombstoneWithHeadersProto;
import android.os.UserHandle;
import android.util.Slog;
import android.util.proto.ProtoInputStream;

import com.android.internal.R;
import com.android.internal.os.Zygote;
import com.android.server.LocalServices;
import com.android.server.os.nano.TombstoneProtos;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import static com.android.server.ext.SseUtils.addNotifAction;

public class TombstoneHandler {
    private static final String TAG = TombstoneHandler.class.getSimpleName();

    private static final int SIGSEGV = 11;
    private static final int SEGV_MTEAERR = 8;
    private static final int SEGV_MTESERR = 9;

    public static final boolean isMemoryTaggingSupported = Zygote.nativeSupportsMemoryTagging();

    public static void handleNewFile(Context ctx, File protoFile) {
        long ts = System.currentTimeMillis();

        try {
            byte[] protoBytes = Files.readAllBytes(protoFile.toPath());

            handleTombstoneBytes(ctx, protoBytes, ts, false);
        } catch (Throwable e) {
            // catch everything to reduce the chance of getting into a crash loop
            Slog.e(TAG, "", e);
        }
    }

    static void handleDropBoxEntry(DropBoxMonitor dm, DropBoxManager.Entry entry) {
        try (InputStream eis = entry.getInputStream()) {
            if (eis == null) {
                Slog.d(TAG, "tombstone entry getInputStream() is null");
                return;
            }

            var pis = new ProtoInputStream(eis);

            byte[] tombstoneBytes = null;
            while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                if (pis.getFieldNumber() == (int) TombstoneWithHeadersProto.TOMBSTONE) {
                    tombstoneBytes = pis.readBytes(TombstoneWithHeadersProto.TOMBSTONE);
                    break;
                }
            }

            if (tombstoneBytes == null) {
                Slog.d(TAG, "tombstoneBytes is null");
                return;
            }

            handleTombstoneBytes(dm.context, tombstoneBytes, entry.getTimeMillis(), true);
        } catch (Throwable e) {
            // catch everything to reduce the chance of getting into a crash loop
            Slog.e(TAG, "", e);
        }
    }

    private static void handleTombstoneBytes(Context ctx, byte[] tombstoneBytes,
            @CurrentTimeMillisLong long timestamp, boolean isHistorical)
            throws IOException
    {
        var tombstone = TombstoneProtos.Tombstone.parseFrom(tombstoneBytes);

        var sb = new StringBuilder();
        sb.append("uid: ");
        sb.append(tombstone.uid);
        sb.append(" (");
        sb.append(tombstone.selinuxLabel);
        sb.append(")\ncmdline:");
        for (String s : tombstone.commandLine) {
            sb.append(' ');
            sb.append(s);
        }
        sb.append("\nprocessUptime: ");
        sb.append(tombstone.processUptime);
        sb.append('s');

        if (!tombstone.abortMessage.isEmpty()) {
            sb.append("\n\nabortMessage: ");
            sb.append(tombstone.abortMessage);
        }

        TombstoneProtos.Signal signal = tombstone.signalInfo;
        if (signal != null) {
            sb.append("\n\nsignal: ");
            sb.append(signal.number);
            sb.append(" (");
            sb.append(signal.name);
            sb.append("), code ");
            sb.append(signal.code);
            sb.append(" (");
            sb.append(signal.codeName);
            sb.append(")");
            if (signal.hasSender) {
                sb.append(", senderUid ");
                sb.append(signal.senderUid);
            }
            if (signal.hasFaultAddress) {
                sb.append(", faultAddr ");
                sb.append(Long.toHexString(signal.faultAddress));
            }
        }

        for (TombstoneProtos.Cause cause : tombstone.causes) {
            sb.append("\ncause: ");
            sb.append(cause.humanReadable);
        }

        var threads = tombstone.threads;
        TombstoneProtos.Thread thread = null;
        if (threads != null) {
            thread = threads.get(tombstone.tid);
        }

        if (thread == null) {
            sb.append("\n\nno thread info");
        } else {
            sb.append("\nthreadName: ");
            sb.append(thread.name);

            if (isMemoryTaggingSupported) {
                sb.append("\nMTE: ");

                final long PR_MTE_TCF_SYNC = 1 << 1;
                final long PR_MTE_TCF_ASYNC = 1 << 2;

                long tac = thread.taggedAddrCtrl;
                if ((tac & (PR_MTE_TCF_SYNC | PR_MTE_TCF_ASYNC)) != 0) {
                    if ((tac & PR_MTE_TCF_ASYNC) != 0) {
                        sb.append("enabled");
                    } else {
                        sb.append("enabled; sync");
                    }
                } else {
                    sb.append("not enabled");
                }
            }

            sb.append("\n\nbacktrace:");

            for (TombstoneProtos.BacktraceFrame frame : thread.currentBacktrace) {
                sb.append("\n    ");
                sb.append(frame.fileName);
                sb.append(" (");
                if (!frame.functionName.isEmpty()) {
                    sb.append(frame.functionName);
                    sb.append('+');
                    sb.append(frame.functionOffset);
                    sb.append(", ");
                }
                sb.append("pc ");
                sb.append(Long.toHexString(frame.relPc));
                sb.append(')');
            }
        }

        String msg = sb.toString();

        String progName = "//no progName//";
        if (tombstone.commandLine.length > 0) {
            String path = tombstone.commandLine[0];
            progName = path.substring(path.lastIndexOf('/') + 1);
        }


        var pm = LocalServices.getService(PackageManagerInternal.class);

        int uid = tombstone.uid;

        boolean isAppUid = Process.isApplicationUid(uid);

        boolean shouldSkip = false;

        if (isAppUid || Process.isIsolatedUid(uid)) {
            int pid = tombstone.pid;

            String firstPackageName = null;
            int packageUid = 0;
            boolean isSystem = false;

            var ami = LocalServices.getService(ActivityManagerInternal.class);
            ActivityManagerInternal.ProcessRecordSnapshot prs = ami.getProcessRecordByPid(pid);
            if (prs != null) {
                ApplicationInfo appInfo = prs.appInfo;
                packageUid = appInfo.uid;
                firstPackageName = appInfo.packageName;
                isSystem = appInfo.isSystemApp();
            } else if (isAppUid) {
                int appId = UserHandle.getAppId(uid);
                List<AndroidPackage> appIdPkgs = pm.getPackagesForAppId(appId);
                if (appIdPkgs.size() == 1) {
                    var pkg = appIdPkgs.get(0);
                    String pkgName = pkg.getPackageName();
                    firstPackageName = pkgName;
                    packageUid = uid;

                    PackageState pkgState = pm.getPackageStateInternal(pkg.getPackageName());
                    if (pkgState != null) {
                        isSystem = pkgState.isSystem() || pkgState.isUpdatedSystemApp();
                    }
                }
            }

            if (firstPackageName == null) {
                Slog.d(TAG, "firstPackageName is null for uid " + packageUid);
                return;
            }

            if (!isSystem) {
                if (!isHistorical) {
                    maybeShowMemtagNotification(ctx, tombstone, msg, packageUid, firstPackageName);
                }
                // rely on the standard crash dialog for non-memtag crashes
                return;
            }

            progName = firstPackageName;
        } else {
            switch (progName) {
                // bootanimation intentionally crashes in some cases
                case "bootanimation" -> {
                    shouldSkip = true;
                }
            }
        }

        if (!"system_server".equals(progName)) {
            if (shouldSkip || !ExtSettings.SHOW_SYSTEM_PROCESS_CRASH_NOTIFICATIONS.get(ctx)) {
                Slog.d(TAG, "skipped crash notification for " + progName + "; msg: " + msg);
                return;
            }
        }

        SystemJournalNotif.showCrash(ctx, progName, msg, timestamp);
    }

    private static void maybeShowMemtagNotification(Context ctx, TombstoneProtos.Tombstone tombstone,
                                                    String errorReport,
                                                    int packageUid, String firstPackageName) {
        TombstoneProtos.Signal signal = tombstone.signalInfo;
        if (signal == null) {
            return;
        }

        boolean proceed = isMemoryTaggingSupported && signal.number == SIGSEGV
                && (signal.code == SEGV_MTEAERR || signal.code == SEGV_MTESERR);

        if (!proceed) {
            return;
        }

        Consumer<Notification.Builder> notifCustomizer = nb -> {
            Intent i = ErrorReportUi.createBaseIntent(ErrorReportUi.ACTION_CUSTOM_REPORT, errorReport);
            i.putExtra(ErrorReportUi.EXTRA_SOURCE_PACKAGE, firstPackageName);

            UserHandle user = UserHandle.of(UserHandle.getUserId(packageUid));
            var pi = PendingIntent.getActivityAsUser(ctx, 0, i,
                    PendingIntent.FLAG_IMMUTABLE, null, user);
            addNotifAction(ctx, pi, R.string.notif_action_more_info, nb);
        };

        AppExploitProtectionNotification.maybeShow(ctx, SettingsIntents.APP_MEMTAG,
                packageUid, firstPackageName,
                GosPackageState.FLAG_FORCE_MEMTAG_SUPPRESS_NOTIF,
                R.string.notif_memtag_crash_title,
                ctx.getText(R.string.notif_text_tap_to_open_settings),
                notifCustomizer);
    }
}
