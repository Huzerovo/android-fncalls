# Keep the modern libxposed entry point and the broadcast sink.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class moe.huzerovo.fncalls.log.TraceReceiver { *; }
-keep class moe.huzerovo.fncalls.FnCallsApp { *; }
