package moe.huzerovo.fncalls.hook;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;

import moe.huzerovo.fncalls.BuildConfig;
import moe.huzerovo.fncalls.config.Config;
import moe.huzerovo.fncalls.log.TraceSink;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs method-entry/exit hooks in one hooked app process.
 *
 * <p>Two sources of hooks are supported:</p>
 * <ul>
 *   <li>explicit system-API rules ({@code Class#method1,method2});</li>
 *   <li>app-internal classes discovered through ClassLoader hooks (or an
 *       optional APK scan).</li>
 * </ul>
 */
public final class Tracer {

    private static final int MAX_LINE_LENGTH = 3072;
    private static final int MAX_TOTAL_APP_METHODS = 6000;
    private static final int MAX_TOTAL_SYSTEM_METHODS = 3000;
    private static final String MODULE_PREFIX = BuildConfig.APPLICATION_ID + ".";

    private final Config config;
    private final XposedModule module;
    private final ClassLoader appLoader;
    private final String packageName;
    private final String processName;
    private final ApplicationInfo appInfo;
    private final List<String> appPrefixes;
    private final List<HookSpec> appRuleSpecs = new ArrayList<>();
    private final TraceSink sink = TraceSink.get();

    private volatile boolean recording;
    private SharedPreferences.OnSharedPreferenceChangeListener recordingListener;

    private final Set<String> appClasses = ConcurrentHashMap.newKeySet();
    private final Set<Member> hookedMembers = ConcurrentHashMap.newKeySet();
    private final Set<String> missingClasses = ConcurrentHashMap.newKeySet();
    private final Map<Member, String> signatures = new ConcurrentHashMap<>();
    private final Set<String> objectMethods = new HashSet<>();

    private final ThreadLocal<ArrayDeque<Frame>> frames =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> inTracer = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> callbackActive = ThreadLocal.withInitial(() -> false);

    private final MethodHook methodHook = new MethodHook();
    private final AtomicBoolean classWatcherInstalled = new AtomicBoolean(false);
    private final AtomicBoolean eagerScanStarted = new AtomicBoolean(false);
    private final AtomicBoolean appCapLogged = new AtomicBoolean(false);
    private final AtomicBoolean systemCapLogged = new AtomicBoolean(false);
    private final AtomicInteger hookFailures = new AtomicInteger();
    private final AtomicInteger appClassCount = new AtomicInteger();
    private final AtomicInteger appMethodCount = new AtomicInteger();
    private final AtomicInteger systemClassCount = new AtomicInteger();
    private final AtomicInteger systemMethodCount = new AtomicInteger();

    public Tracer(Config config, XposedModule module, String packageName,
                  String processName, ApplicationInfo appInfo, ClassLoader appLoader) {
        this.config = config;
        this.module = module;
        this.appLoader = appLoader;
        this.packageName = packageName;
        this.processName = processName == null ? packageName : processName;
        this.appInfo = appInfo;
        this.appPrefixes = config.appClassPrefixes(packageName);
        this.recording = config.enabled;
        for (String rule : config.appRules) {
            HookSpec spec = HookSpec.parse(rule);
            if (spec != null) {
                appRuleSpecs.add(spec);
            }
        }
        Collections.addAll(objectMethods, "toString", "hashCode", "equals", "clone", "finalize");
    }

    public void install() {
        sink.init(packageName, processName, config);

        Application application = currentApplication();
        if (application != null) {
            sink.setContext(application);
        }
        watchRecordingSwitch();
        installContextHook();
        banner();

        if (config.traceApp || !appRuleSpecs.isEmpty()) {
            installClassWatchers();
            try {
                hookApplicationClass();
            } catch (Throwable t) {
                TraceSink.frameworkLog("Hook Application 类失败: " + t);
            }
            if (config.eagerScan) {
                startEagerScan();
            }
        }
        startSystemRulesAsync();
    }

    /**
     * Hooks stay installed for the whole process lifetime so that the UI's
     * start/stop switch takes effect on running apps. While stopped, the hooks
     * only pass the call through without formatting or logging anything.
     */
    private void watchRecordingSwitch() {
        try {
            SharedPreferences prefs = module.getRemotePreferences(Config.PREFS_NAME);
            recording = Config.readEnabled(prefs);
            recordingListener = (changed, key) -> recording = Config.readEnabled(changed);
            prefs.registerOnSharedPreferenceChangeListener(recordingListener);
        } catch (Throwable t) {
            TraceSink.frameworkLog("无法监听记录开关，将使用启动时的配置: " + t);
        }
    }

    /**
     * Installing hundreds of method hooks on the app main thread can exceed
     * Android's process-start timeout. Keep startup cheap and install the
     * explicit system rules on a daemon thread shortly afterwards.
     */
    private void startSystemRulesAsync() {
        if (!config.traceSystem) {
            installedSummary();
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    installSystemRules();
                } catch (Throwable t) {
                    TraceSink.frameworkLog("安装系统 API 规则失败: " + t);
                }
                installedSummary();
                TraceSink.frameworkLog("系统 API 追踪已启动: " + processName);
            }
        }, "FnCalls-rules");
        thread.setDaemon(true);
        thread.start();
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, null);
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installContextHook() {
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, null);
            Method method = instrumentation.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            module.hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            if (!chain.getArgs().isEmpty()
                                    && chain.getArg(0) instanceof Application) {
                                Application app = (Application) chain.getArg(0);
                                sink.setContext(app);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            TraceSink.frameworkLog("无法安装 Application 上下文 Hook: " + t);
        }
    }

    private void installSystemRules() {        for (String line : config.systemRules) {
            HookSpec spec = HookSpec.parse(line);
            if (spec == null) {
                warn("忽略无效规则: " + line);
                continue;
            }
            Class<?> clazz = findClass(spec.className);
            if (clazz == null) {
                if (missingClasses.add(spec.className)) {
                    warn("找不到规则类: " + spec.className);
                }
                continue;
            }
            int count = hookSystemClass(clazz, spec);
            if (count > 0) {
                systemClassCount.incrementAndGet();
            }
        }
    }

    private int hookSystemClass(Class<?> clazz, HookSpec spec) {
        int count = 0;
        boolean wildcard = spec.methodPatterns.contains("*");
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (count >= config.maxMethodsPerClass
                        || systemMethodCount.get() >= MAX_TOTAL_SYSTEM_METHODS) {
                    break;
                }
                if (!spec.matches(method) || !isHookable(method)) {
                    continue;
                }
                if ("java.lang.reflect.Constructor".equals(method.getDeclaringClass().getName())
                        && "newInstance".equals(method.getName())) {
                    // Vector itself reflects through Constructor.newInstance.
                    continue;
                }
                if (wildcard && objectMethods.contains(method.getName())) {
                    continue;
                }
                if (hookMember(method)) {
                    count++;
                    if (systemMethodCount.incrementAndGet() >= MAX_TOTAL_SYSTEM_METHODS
                            && systemCapLogged.compareAndSet(false, true)) {
                        warn("系统 API Hook 已达到总上限 " + MAX_TOTAL_SYSTEM_METHODS
                                + "，后续规则将被忽略");
                    }
                }
            }

            boolean includeConstructors = spec.methodPatterns.contains("<init>")
                    || (wildcard && config.includeConstructors);
            if (includeConstructors) {
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (count >= config.maxMethodsPerClass
                            || systemMethodCount.get() >= MAX_TOTAL_SYSTEM_METHODS) {
                        break;
                    }
                    if (constructor.isSynthetic() || Modifier.isAbstract(constructor.getModifiers())) {
                        continue;
                    }
                    if (hookMember(constructor)) {
                        count++;
                        systemMethodCount.incrementAndGet();
                    }
                }
            }
        } catch (Throwable t) {
            warn("Hook 规则失败 " + spec.raw + ": " + t);
        }
        return count;
    }

    private void installClassWatchers() {
        if (!classWatcherInstalled.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.Hooker classLoadHook = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (result instanceof Class) {
                    maybeHookAppClass((Class<?>) result);
                }
                return result;
            }
        };
        try {
            // Only loadClass is hooked. Hooking Class.forName() or
            // ClassLoader.findClass() interferes with ART's class-resolution
            // path on some ROMs (notably during androidx.startup provider
            // initialization) and can make the target app crash at boot.
            for (Method method : ClassLoader.class.getDeclaredMethods()) {
                if ("loadClass".equals(method.getName()) && isHookable(method)) {
                    hookMember(method, classLoadHook);
                }
            }
        } catch (Throwable t) {
            TraceSink.frameworkLog("安装类加载监听失败: " + t);
        }
    }

    private void hookApplicationClass() {
        if (appInfo == null || appInfo.className == null) {
            return;
        }
        try {
            Class<?> applicationClass = Class.forName(appInfo.className, false, appLoader);
            maybeHookAppClass(applicationClass);
        } catch (Throwable t) {
            // Not fatal: the class will be seen by the ClassLoader hook if it is
            // loaded later.
        }
    }

    private void maybeHookAppClass(Class<?> clazz) {
        if (clazz == null
                || TraceSink.isInternalThread()
                || Boolean.TRUE.equals(callbackActive.get())
                || Boolean.TRUE.equals(inTracer.get())) {
            return;
        }
        inTracer.set(true);
        try {
            hookAppClass(clazz);
        } catch (Throwable t) {
            HookLog.error("hook app class failed: " + clazz.getName(), t);
        } finally {
            inTracer.set(false);
        }
    }

    private void hookAppClass(Class<?> clazz) {
        String className = clazz.getName();
        if (className == null
                || className.startsWith(MODULE_PREFIX)
                || clazz.isInterface()
                || clazz.isArray()
                || clazz.isPrimitive()
                || clazz.getClassLoader() == null
                || !mightMatchAppClass(className)) {
            return;
        }
        if (!appClasses.add(className)) {
            return;
        }

        int count = 0;
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (count >= config.maxMethodsPerClass
                        || appMethodCount.get() >= MAX_TOTAL_APP_METHODS) {
                    break;
                }
                if (method.isSynthetic() || method.isBridge()
                        || !isHookable(method)
                        || objectMethods.contains(method.getName())
                        || !shouldHookAppMember(className, method)) {
                    continue;
                }
                if (hookMember(method)) {
                    count++;
                    if (appMethodCount.incrementAndGet() >= MAX_TOTAL_APP_METHODS
                            && appCapLogged.compareAndSet(false, true)) {
                        warn("APP 内部 Hook 已达到总上限 "
                                + MAX_TOTAL_APP_METHODS + "，后续类将被忽略");
                    }
                }
            }
            if (config.includeConstructors) {
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (count >= config.maxMethodsPerClass
                            || appMethodCount.get() >= MAX_TOTAL_APP_METHODS) {
                        break;
                    }
                    if (constructor.isSynthetic() || Modifier.isAbstract(constructor.getModifiers())
                            || !shouldHookAppMember(className, constructor)) {
                        continue;
                    }
                    if (hookMember(constructor)) {
                        count++;
                        appMethodCount.incrementAndGet();
                    }
                }
            }
        } catch (Throwable t) {
            HookLog.error("hookAppClass methods failed: " + className, t);
        }

        if (count > 0) {
            int classes = appClassCount.incrementAndGet();
            if (classes % 100 == 0) {
                TraceSink.frameworkLog("APP 内部类 Hook 进度: " + classes
                        + " 个类 / " + appMethodCount.get() + " 个方法");
            }
        } else {
            // Allow a later Activity hook / retry to try again.
            appClasses.remove(className);
        }
    }

    private boolean mightMatchAppClass(String className) {
        if (config.traceApp && matchesAppPrefix(className)) {
            return true;
        }
        for (HookSpec spec : appRuleSpecs) {
            if (spec.matchesClass(className)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAppPrefix(String className) {
        for (String prefix : appPrefixes) {
            if (className.equals(prefix) || className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldHookAppMember(String className, Member member) {
        if (config.traceApp && matchesAppPrefix(className)) {
            return true;
        }
        for (HookSpec spec : appRuleSpecs) {
            if (spec.matchesClass(className) && spec.matches(member)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHookable(Method method) {
        int modifiers = method.getModifiers();
        return !Modifier.isAbstract(modifiers)
                && !Modifier.isNative(modifiers)
                && !method.isSynthetic()
                && !method.isBridge();
    }

    private boolean hookMember(Member member) {
        return hookMember(member, methodHook);
    }

    private boolean hookMember(Member member, XposedInterface.Hooker hooker) {
        if (member == null || hookedMembers.contains(member)) {
            return false;
        }
        try {
            XposedInterface.HookHandle handle;
            if (member instanceof Method) {
                handle = module.hook((Method) member).intercept(hooker);
            } else if (member instanceof Constructor) {
                handle = module.hook((Constructor<?>) member).intercept(hooker);
            } else {
                return false;
            }
            if (handle == null) {
                return false;
            }
            hookedMembers.add(member);
            signatures.putIfAbsent(member, ValueFormatter.signature(member));
            return true;
        } catch (Throwable t) {
            if (hookFailures.incrementAndGet() <= 20) {
                warn("Hook 失败 " + member + ": " + t);
            }
            return false;
        }
    }

    private Class<?> findClass(String name) {
        try {
            return Class.forName(name, false, appLoader);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(name, false, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startEagerScan() {
        if (appInfo == null || !eagerScanStarted.compareAndSet(false, true)) {
            return;
        }
        final ApplicationInfo info = appInfo;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Set<String> paths = new LinkedHashSet<>();
                addPath(paths, info.sourceDir);
                addPath(paths, info.publicSourceDir);
                if (info.splitSourceDirs != null) {
                    Collections.addAll(paths, info.splitSourceDirs);
                }
                if (info.splitPublicSourceDirs != null) {
                    Collections.addAll(paths, info.splitPublicSourceDirs);
                }

                int before = appClassCount.get();
                for (String path : paths) {
                    scanDex(path);
                }
                TraceSink.frameworkLog("APK 扫描完成: 新增 " + (appClassCount.get() - before)
                        + " 个类，共 " + appMethodCount.get() + " 个方法");
            }
        }, "FnCalls-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private static void addPath(Set<String> paths, String path) {
        if (path != null && !path.isEmpty()) {
            paths.add(path);
        }
    }

    @SuppressWarnings("unchecked")
    private void scanDex(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            return;
        }
        Object dex = null;
        try {
            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            Constructor<?> constructor = dexFileClass.getDeclaredConstructor(File.class);
            constructor.setAccessible(true);
            dex = constructor.newInstance(file);
            Method entriesMethod = dexFileClass.getMethod("entries");
            Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dex);
            int processed = 0;
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (className == null || !mightMatchAppClass(className)) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(className, false, appLoader);
                    maybeHookAppClass(clazz);
                } catch (Throwable ignored) {
                }
                if (++processed % 64 == 0) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            TraceSink.frameworkLog("扫描 " + path + " 失败（可通过 ClassLoader 监听继续工作）: " + t);
        } finally {
            if (dex != null) {
                try {
                    dex.getClass().getMethod("close").invoke(dex);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void banner() {
        String version = BuildConfig.VERSION_NAME;
        String api = String.valueOf(module.getApiVersion());
        String appClass = appInfo == null ? "?" : String.valueOf(appInfo.className);
        StringBuilder line = new StringBuilder();
        line.append("=== FnCalls ").append(version)
                .append(" | pkg=").append(packageName)
                .append(" | proc=").append(processName)
                .append(" | pid=").append(Process.myPid())
                .append(" | sdk=").append(Build.VERSION.SDK_INT)
                .append(" | api=").append(api)
                .append(" | app=").append(appClass)
                .append(" ===");
        sink.trace(line.toString());

        StringBuilder summary = new StringBuilder();
        summary.append("[FnCalls] traceApp=").append(config.traceApp)
                .append(" prefixes=").append(appPrefixes)
                .append(" appRules=").append(config.appRules.size())
                .append(" traceSystem=").append(config.traceSystem)
                .append(" rules=").append(config.systemRules.size())
                .append(" logArgs=").append(config.logArgs)
                .append(" logReturn=").append(config.logReturn)
                .append(" logStack=").append(config.logStack)
                .append(" maxDepth=").append(config.maxDepth)
                .append(" maxMethods/class=").append(config.maxMethodsPerClass);
        sink.trace(bounded(summary.toString()));
    }

    private void installedSummary() {
        String summary = "[FnCalls] 注入完成 | systemClasses=" + systemClassCount.get()
                + " systemMethods=" + systemMethodCount.get()
                + " appClasses=" + appClassCount.get()
                + " appMethods=" + appMethodCount.get();
        sink.trace(summary);
        TraceSink.frameworkLog(summary);
    }

    private void warn(String message) {
        TraceSink.frameworkLog(message);
        try {
            sink.trace("[FnCalls] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static String bounded(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "…" : line;
    }

    private static String duration(long nanos) {
        long micros = Math.max(0, nanos) / 1000;
        if (micros < 1000) {
            return micros + "µs";
        }
        return String.format(Locale.US, "%.2fms", micros / 1000.0);
    }

    private static final class Frame {
        long startNanos;
        int depth;
        boolean logged;
    }

    private final class MethodHook implements XposedInterface.Hooker {

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            if (!recording
                    || TraceSink.isInternalThread()
                    || Boolean.TRUE.equals(callbackActive.get())
                    || Boolean.TRUE.equals(inTracer.get())) {
                return chain.proceed();
            }

            Executable executable = chain.getExecutable();
            ArrayDeque<Frame> stack = frames.get();
            Frame frame = new Frame();
            frame.startNanos = System.nanoTime();
            stack.push(frame);
            try {
                int depth = stack.size() - 1;
                if (config.maxDepth <= 0 || depth < config.maxDepth) {
                    callbackActive.set(true);
                    inTracer.set(true);
                    try {
                        frame.logged = true;
                        frame.depth = depth;
                        logBefore(chain, executable, depth);
                    } catch (Throwable t) {
                        frame.logged = false;
                    } finally {
                        inTracer.set(false);
                        callbackActive.set(false);
                    }
                }

                Object result = null;
                Throwable error = null;
                // The target method executes with callbackActive/inTracer
                // cleared so nested application calls are traced normally.
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    error = t;
                }

                if (frame.logged && config.logReturn) {
                    callbackActive.set(true);
                    inTracer.set(true);
                    try {
                        logAfter(chain, executable, frame, result, error);
                    } catch (Throwable ignored) {
                    } finally {
                        inTracer.set(false);
                        callbackActive.set(false);
                    }
                }

                if (error != null) {
                    throw error;
                }
                return result;
            } finally {
                stack.pop();
                callbackActive.set(false);
            }
        }

        private void logBefore(XposedInterface.Chain chain, Executable executable, int depth) {
            String signature = signature(executable);
            StringBuilder line = new StringBuilder(192);
            line.append(ValueFormatter.time())
                    .append(" [").append(Thread.currentThread().getName())
                    .append("] D").append(depth).append(' ')
                    .append(ValueFormatter.indent(depth))
                    .append(executable instanceof Constructor ? "→ new " : "→ ")
                    .append(signature);

            if (config.logArgs) {
                line.append(ValueFormatter.arguments(chain.getArgs().toArray()));
            }
            Object receiver = chain.getThisObject();
            if (receiver != null) {
                line.append(" on ").append(ValueFormatter.receiver(receiver));
            }
            if (config.logStack) {
                String stackText = ValueFormatter.stack(config.stackDepth);
                if (!stackText.isEmpty()) {
                    line.append(" | caller=").append(stackText);
                }
            }
            sink.trace(bounded(line.toString()));
        }

        private void logAfter(XposedInterface.Chain chain, Executable executable,
                              Frame frame, Object result, Throwable error) {
            String signature = signature(executable);
            StringBuilder line = new StringBuilder(192);
            line.append(ValueFormatter.time())
                    .append(" [").append(Thread.currentThread().getName())
                    .append("] D").append(frame.depth).append(' ')
                    .append(ValueFormatter.indent(frame.depth))
                    .append("← ").append(signature);

            if (error != null) {
                line.append(" !! ").append(ValueFormatter.format(error, 512));
            } else {
                line.append(" = ");
                if (executable instanceof Method
                        && ((Method) executable).getReturnType() == void.class) {
                    line.append("void");
                } else {
                    line.append(ValueFormatter.format(result, 512));
                }
            }
            line.append(" (").append(duration(System.nanoTime() - frame.startNanos)).append(')');
            sink.trace(bounded(line.toString()));
        }

        private String signature(Executable executable) {
            String signature = signatures.get(executable);
            if (signature == null) {
                signature = ValueFormatter.signature(executable);
                signatures.put(executable, signature);
            }
            return signature;
        }
    }
}
