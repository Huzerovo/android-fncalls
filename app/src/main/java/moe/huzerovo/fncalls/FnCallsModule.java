package moe.huzerovo.fncalls;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import moe.huzerovo.fncalls.config.Config;
import moe.huzerovo.fncalls.hook.HookConfig;
import moe.huzerovo.fncalls.hook.HookLog;
import moe.huzerovo.fncalls.hook.Tracer;

/**
 * Modern libxposed module entry point (API 101/102).
 *
 * <p>Vector loads this class through {@code META-INF/xposed/java_init.list}
 * instead of the legacy {@code assets/xposed_init} path, so the log should show
 * the modern module manager rather than {@code VectorLegacyBridge}.</p>
 */
public final class FnCallsModule extends XposedModule {

    private static final String TAG = "FnCalls";

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private volatile String processName = "";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        HookLog.install(this);
        if (param.isSystemServer()) {
            log(Log.INFO, TAG, "跳过 system_server");
        }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            handlePackage(param.getPackageName(), param.getApplicationInfo(),
                    param.getClassLoader(), param.isFirstPackage());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "onPackageReady 异常", t);
        }
    }

    private void handlePackage(String packageName, ApplicationInfo appInfo,
                               ClassLoader classLoader, boolean firstPackage) {
        if (packageName == null || BuildConfig.APPLICATION_ID.equals(packageName)) {
            return;
        }
        if ("android".equals(packageName) || "system_server".equals(processName)) {
            log(Log.INFO, TAG, "跳过系统进程: " + processName);
            return;
        }

        Config config = HookConfig.load(this);
        // The framework only injects into Vector-scoped processes. Any package
        // callbacks beyond that come from secondary packages sharing the
        // process, so filter those instead of asking the user for a target list.
        if (!processName.isEmpty()
                && !packageName.equals(processName)
                && !processName.startsWith(packageName + ":")) {
            return;
        }
        if (!firstPackage && !config.traceSecondary) {
            return;
        }
        if (!installed.compareAndSet(false, true)) {
            return;
        }

        try {
            new Tracer(config, this, packageName, processName, appInfo, classLoader).install();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "注入失败 " + processName, t);
        }
    }
}
