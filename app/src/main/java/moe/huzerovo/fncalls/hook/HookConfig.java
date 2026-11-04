package moe.huzerovo.fncalls.hook;

import moe.huzerovo.fncalls.config.Config;

import io.github.libxposed.api.XposedModule;

/**
 * Modern-API configuration reader. Remote preferences are written by the module
 * UI through {@code XposedService} and are read-only in hooked processes.
 */
public final class HookConfig {

    private HookConfig() {
    }

    public static Config load(XposedModule module) {
        try {
            return Config.from(module.getRemotePreferences(Config.PREFS_NAME));
        } catch (Throwable t) {
            HookLog.error("读取远程配置失败，使用默认值", t);
            return Config.defaults();
        }
    }
}
