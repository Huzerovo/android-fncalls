package moe.huzerovo.fncalls.config;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable per-process configuration snapshot.
 *
 * <p>The module UI writes remote preferences through the libxposed service.
 * Hooked apps read the same group through {@code XposedModule.getRemotePreferences()}.
 * The framework handles cross-process synchronization.</p>
 */
public final class Config {

    public static final String PREFS_NAME = "fncalls_config";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_TRACE_SECONDARY = false;
    public static final boolean DEFAULT_TRACE_APP = true;
    public static final boolean DEFAULT_EAGER_SCAN = false;
    public static final boolean DEFAULT_TRACE_SYSTEM = true;
    public static final boolean DEFAULT_LOG_ARGS = true;
    public static final boolean DEFAULT_LOG_RETURN = true;
    public static final boolean DEFAULT_LOG_STACK = false;
    public static final boolean DEFAULT_LOG_XPOSED = false;
    public static final boolean DEFAULT_INCLUDE_CONSTRUCTORS = false;
    public static final int DEFAULT_STACK_DEPTH = 6;
    public static final int DEFAULT_MAX_DEPTH = 10;
    public static final int DEFAULT_MAX_METHODS = 60;
    public static final int DEFAULT_MAX_LOG_MB = 8;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TARGETS = "targets";
    private static final String KEY_TRACE_SECONDARY = "trace_secondary";
    private static final String KEY_TRACE_APP = "trace_app";
    private static final String KEY_APP_PACKAGES = "app_packages";
    private static final String KEY_APP_RULES = "app_rules";
    private static final String KEY_EAGER_SCAN = "eager_scan";
    private static final String KEY_TRACE_SYSTEM = "trace_system";
    private static final String KEY_SYSTEM_RULES = "system_rules";
    private static final String KEY_LOG_ARGS = "log_args";
    private static final String KEY_LOG_RETURN = "log_return";
    private static final String KEY_LOG_STACK = "log_stack";
    private static final String KEY_LOG_XPOSED = "log_xposed";
    private static final String KEY_INCLUDE_CONSTRUCTORS = "include_constructors";
    private static final String KEY_STACK_DEPTH = "stack_depth";
    private static final String KEY_MAX_DEPTH = "max_depth";
    private static final String KEY_MAX_METHODS = "max_methods";
    private static final String KEY_MAX_LOG_MB = "max_log_mb";

    public final boolean enabled;
    public final List<String> targets;
    public final boolean traceSecondary;
    public final boolean traceApp;
    public final List<String> appPackages;
    public final List<String> appRules;
    public final boolean eagerScan;
    public final boolean traceSystem;
    public final List<String> systemRules;
    public final boolean logArgs;
    public final boolean logReturn;
    public final boolean logStack;
    public final boolean logXposed;
    public final boolean includeConstructors;
    public final int stackDepth;
    public final int maxDepth;
    public final int maxMethodsPerClass;
    public final int maxLogMb;

    private final List<Pattern> targetPatterns;

    public Config(boolean enabled,
                  List<String> targets,
                  boolean traceSecondary,
                  boolean traceApp,
                  List<String> appPackages,
                  List<String> appRules,
                  boolean eagerScan,
                  boolean traceSystem,
                  List<String> systemRules,
                  boolean logArgs,
                  boolean logReturn,
                  boolean logStack,
                  boolean logXposed,
                  boolean includeConstructors,
                  int stackDepth,
                  int maxDepth,
                  int maxMethodsPerClass,
                  int maxLogMb) {
        this.enabled = enabled;
        this.targets = immutable(targets);
        this.traceSecondary = traceSecondary;
        this.traceApp = traceApp;
        this.appPackages = immutable(appPackages);
        this.appRules = immutable(appRules);
        this.eagerScan = eagerScan;
        this.traceSystem = traceSystem;
        this.systemRules = immutable(systemRules);
        this.logArgs = logArgs;
        this.logReturn = logReturn;
        this.logStack = logStack;
        this.logXposed = logXposed;
        this.includeConstructors = includeConstructors;
        this.stackDepth = clamp(stackDepth, 0, 32);
        this.maxDepth = clamp(maxDepth, 0, 100);
        this.maxMethodsPerClass = clamp(maxMethodsPerClass, 1, 1000);
        this.maxLogMb = clamp(maxLogMb, 1, 64);

        List<Pattern> patterns = new ArrayList<>();
        for (String target : this.targets) {
            patterns.add(Pattern.compile(globToRegex(target)));
        }
        this.targetPatterns = Collections.unmodifiableList(patterns);
    }

    public Config withTargets(List<String> newTargets) {
        return new Config(enabled, newTargets, traceSecondary, traceApp, appPackages,
                appRules, eagerScan, traceSystem, systemRules, logArgs, logReturn, logStack,
                logXposed, includeConstructors, stackDepth, maxDepth,
                maxMethodsPerClass, maxLogMb);
    }

    public Config withEnabled(boolean newEnabled) {
        return new Config(newEnabled, targets, traceSecondary, traceApp, appPackages,
                appRules, eagerScan, traceSystem, systemRules, logArgs, logReturn, logStack,
                logXposed, includeConstructors, stackDepth, maxDepth,
                maxMethodsPerClass, maxLogMb);
    }

    public static Config defaults() {
        return new Config(
                DEFAULT_ENABLED,
                Collections.emptyList(),
                DEFAULT_TRACE_SECONDARY,
                DEFAULT_TRACE_APP,
                Collections.emptyList(),
                Collections.emptyList(),
                DEFAULT_EAGER_SCAN,
                DEFAULT_TRACE_SYSTEM,
                splitRules(Presets.DEFAULT_RULES_TEXT),
                DEFAULT_LOG_ARGS,
                DEFAULT_LOG_RETURN,
                DEFAULT_LOG_STACK,
                DEFAULT_LOG_XPOSED,
                DEFAULT_INCLUDE_CONSTRUCTORS,
                DEFAULT_STACK_DEPTH,
                DEFAULT_MAX_DEPTH,
                DEFAULT_MAX_METHODS,
                DEFAULT_MAX_LOG_MB
        );
    }

    public static Config from(SharedPreferences prefs) {
        return new Config(
                prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                splitList(prefs.getString(KEY_TARGETS, "")),
                prefs.getBoolean(KEY_TRACE_SECONDARY, DEFAULT_TRACE_SECONDARY),
                prefs.getBoolean(KEY_TRACE_APP, DEFAULT_TRACE_APP),
                splitList(prefs.getString(KEY_APP_PACKAGES, "")),
                splitRules(prefs.getString(KEY_APP_RULES, "")),
                prefs.getBoolean(KEY_EAGER_SCAN, DEFAULT_EAGER_SCAN),
                prefs.getBoolean(KEY_TRACE_SYSTEM, DEFAULT_TRACE_SYSTEM),
                splitRules(prefs.getString(KEY_SYSTEM_RULES, Presets.DEFAULT_RULES_TEXT)),
                prefs.getBoolean(KEY_LOG_ARGS, DEFAULT_LOG_ARGS),
                prefs.getBoolean(KEY_LOG_RETURN, DEFAULT_LOG_RETURN),
                prefs.getBoolean(KEY_LOG_STACK, DEFAULT_LOG_STACK),
                prefs.getBoolean(KEY_LOG_XPOSED, DEFAULT_LOG_XPOSED),
                prefs.getBoolean(KEY_INCLUDE_CONSTRUCTORS, DEFAULT_INCLUDE_CONSTRUCTORS),
                prefs.getInt(KEY_STACK_DEPTH, DEFAULT_STACK_DEPTH),
                prefs.getInt(KEY_MAX_DEPTH, DEFAULT_MAX_DEPTH),
                prefs.getInt(KEY_MAX_METHODS, DEFAULT_MAX_METHODS),
                prefs.getInt(KEY_MAX_LOG_MB, DEFAULT_MAX_LOG_MB)
        );
    }

    public void saveTo(SharedPreferences.Editor editor) {
        editor.putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_TARGETS, join(targets))
                .putBoolean(KEY_TRACE_SECONDARY, traceSecondary)
                .putBoolean(KEY_TRACE_APP, traceApp)
                .putString(KEY_APP_PACKAGES, join(appPackages))
                .putString(KEY_APP_RULES, join(appRules))
                .putBoolean(KEY_EAGER_SCAN, eagerScan)
                .putBoolean(KEY_TRACE_SYSTEM, traceSystem)
                .putString(KEY_SYSTEM_RULES, join(systemRules))
                .putBoolean(KEY_LOG_ARGS, logArgs)
                .putBoolean(KEY_LOG_RETURN, logReturn)
                .putBoolean(KEY_LOG_STACK, logStack)
                .putBoolean(KEY_LOG_XPOSED, logXposed)
                .putBoolean(KEY_INCLUDE_CONSTRUCTORS, includeConstructors)
                .putInt(KEY_STACK_DEPTH, stackDepth)
                .putInt(KEY_MAX_DEPTH, maxDepth)
                .putInt(KEY_MAX_METHODS, maxMethodsPerClass)
                .putInt(KEY_MAX_LOG_MB, maxLogMb);
    }

    public static boolean readEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static int readMaxLogMb(SharedPreferences prefs) {
        return clamp(prefs.getInt(KEY_MAX_LOG_MB, DEFAULT_MAX_LOG_MB), 1, 64);
    }

    public static boolean saveSystemRules(SharedPreferences prefs, List<String> rules) {
        try {
            return prefs.edit()
                    .putString(KEY_SYSTEM_RULES, join(rules))
                    .putBoolean("__initialized", true)
                    .commit();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean saveAppRules(SharedPreferences prefs, List<String> rules) {
        try {
            return prefs.edit()
                    .putString(KEY_APP_RULES, join(rules))
                    .putBoolean("__initialized", true)
                    .commit();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean matchesTarget(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        for (Pattern pattern : targetPatterns) {
            if (pattern.matcher(packageName).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Class name prefixes that belong to the app itself. The target package is
     * always included; the UI field only adds extra prefixes (libraries, plugin
     * loaders, ...).
     */
    public List<String> appClassPrefixes(String packageName) {
        List<String> result = new ArrayList<>();
        if (packageName != null && !packageName.isEmpty()) {
            result.add(packageName);
        }
        for (String prefix : appPackages) {
            if (prefix != null && !prefix.isEmpty() && !result.contains(prefix)) {
                result.add(prefix);
            }
        }
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    public static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\\n;]")) {
            String value = part.trim();
            if (!value.isEmpty() && !value.startsWith("#") && !value.startsWith("//")) {
                out.add(value);
            }
        }
        return out;
    }

    public static List<String> splitRules(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("\\r?\\n")) {
            String value = part.trim();
            if (!value.isEmpty() && !value.startsWith("#") && !value.startsWith("//")) {
                out.add(value);
            }
        }
        return out;
    }

    public static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                case '.':
                case '$':
                case '^':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '+':
                case '|':
                case '\\':
                    regex.append('\\').append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        return regex.append('$').toString();
    }
}
