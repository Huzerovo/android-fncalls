package moe.huzerovo.fncalls.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Small indirection so tracing code can log through the modern libxposed
 * framework without holding a static legacy XposedBridge reference.
 */
public final class HookLog {

    private static final String TAG = "FnCalls";
    private static volatile XposedModule module;

    private HookLog() {
    }

    public static void install(XposedModule instance) {
        module = instance;
    }

    public static void info(String message) {
        XposedModule current = module;
        if (current != null) {
            try {
                current.log(Log.INFO, TAG, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, message);
    }

    public static void warn(String message) {
        XposedModule current = module;
        if (current != null) {
            try {
                current.log(Log.WARN, TAG, message);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.w(TAG, message);
    }

    public static void error(String message, Throwable throwable) {
        XposedModule current = module;
        if (current != null) {
            try {
                current.log(Log.ERROR, TAG, message, throwable);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.e(TAG, message, throwable);
    }
}
