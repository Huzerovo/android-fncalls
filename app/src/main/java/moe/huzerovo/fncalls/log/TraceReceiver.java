package moe.huzerovo.fncalls.log;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;

/**
 * Receives trace batches from hooked app processes.
 *
 * <p>A broadcast is used instead of the original ContentProvider because
 * Android 11+ package visibility filters cross-app provider access for apps
 * that do not declare a &lt;queries&gt; entry for the module. Broadcast sending
 * is not restricted by the caller's package visibility, so an explicit
 * broadcast works without modifying the target APK.</p>
 */
public final class TraceReceiver extends BroadcastReceiver {

    public static final String ACTION_APPEND = "moe.huzerovo.fncalls.action.APPEND";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_LINES = "lines";
    public static final String EXTRA_MAX_MB = "max_mb";

    private static final int MAX_LINES = 256;
    private static final int MAX_TOTAL_CHARS = 256 * 1024;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_APPEND.equals(intent.getAction())) {
            return;
        }
        try {
            String packageName = intent.getStringExtra(EXTRA_PACKAGE);
            if (packageName == null) {
                packageName = "unknown";
            }
            if (packageName.length() > 200) {
                packageName = packageName.substring(0, 200);
            }
            ArrayList<String> lines = intent.getStringArrayListExtra(EXTRA_LINES);
            if (lines == null || lines.size() > MAX_LINES) {
                return;
            }
            int total = 0;
            for (String line : lines) {
                total += line == null ? 0 : line.length();
                if (total > MAX_TOTAL_CHARS) {
                    return;
                }
            }
            int maxMb = intent.getIntExtra(EXTRA_MAX_MB, 8);
            if (maxMb < 1) {
                maxMb = 1;
            } else if (maxMb > 64) {
                maxMb = 64;
            }
            TraceFiles.append(context, packageName, lines, maxMb);
        } catch (Throwable ignored) {
            // A malformed/logging failure must never crash the module process.
        }
    }
}
