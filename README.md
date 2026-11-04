# FnCalls

一个运行在 **Vector**（ART 注入器，基于 LSPlant）上的 Xposed 模块，用于记录目标 App 的**内部方法**与**系统 API** 调用过程。

模块本身既是配置界面，也是日志查看器。Hook 只发生在被选中的目标 App 进程内存中，不修改 APK、不注入 system_server，卸载模块或重启设备即可完全撤销。

- 模块包名：`moe.huzerovo.fncalls`
- 日志接收器：`moe.huzerovo.fncalls.log.TraceReceiver`

## 功能

- **系统 API 调用记录**：按「类名#方法名」规则 Hook Java/Kotlin 系统 API，记录进入/退出、参数、返回值、异常与耗时。
- **App 内部方法记录**：自动发现 Vector 作用域内应用包名下的类，在类加载时 Hook 其方法；支持单独的「APP 内部规则」编辑器；可选实验性 APK 类扫描。
- **开始 / 停止**：主页一个按钮控制记录状态。「开始记录」保存当前设置并启动作用域内的应用；「停止记录」对运行中的进程即时生效，无需重启。
- **调用树**：按线程记录调用深度，日志中使用缩进和 `→ / ←` 标记展示调用过程。
- **常用预设**：文件 IO、网络、反射、组件生命周期、SQLite、设备信息、定位、加密、WebView、日志、敏感权限等。
- **批量日志通道**：目标进程通过显式广播把日志批量写入模块进程，UI 内的「日志」页可按进程切换、搜索、按类型过滤、清空、分享，并通过系统文件选择器导出为 `.txt`。
- **安全边界**：只处理 Vector 作用域内的进程、每类方法上限、总方法上限、最大调用深度、日志大小轮转。

## 技术方案

| 组件 | 说明 |
| --- | --- |
| 注入器 | Vector（现代 libxposed API，底层为 LSPlant + Dobby） |
| 模块 API | `io.github.libxposed:api:102.0.0`，拦截器链 `hook(...).intercept(...)` |
| 入口 | `META-INF/xposed/java_init.list` → `moe.huzerovo.fncalls.FnCallsModule` |
| 模块元数据 | `META-INF/xposed/module.prop`（`minApiVersion=101`、`targetApiVersion=102`） |
| App 内部发现 | Hook `ClassLoader.loadClass`，按类名前缀匹配后 Hook 声明方法 |
| 系统 API | 解析 UI 中的 `Class#method1,method2` 规则，规则在 App 启动完成后由后台线程安装 |
| 配置 | UI 通过 `io.github.libxposed:service` 写入远程偏好；目标进程用 `getRemotePreferences()` 读取 |
| 日志 | 目标进程缓冲 → 显式广播 → `TraceReceiver` 写入模块私有目录 |
| 日志查看 | 模块 UI 直接读取 `files/traces/<package>.log`，主/子进程共用，支持过滤/清空/FileProvider 分享 |

> 迁移到现代 API 后，Vector 日志中应看到 `VectorModuleManager` 加载 `FnCallsModule`，不再出现 `VectorLegacyBridge`。

日志写入发生在目标 App 进程，而 UI 在模块进程，两者 UID 不同。这里使用**显式广播**而不是 ContentProvider：Android 11+ 的包可见性会过滤第三方 App 对模块 Provider 的访问（`forceQueryable` 只在通过 `pm install --force-queryable` 安装时可靠），而 AOSP 明确不会因包可见性限制广播发送。目标 App 无需声明 `<queries>`，也无需修改 APK。

## 构建

要求：

- Android Studio / JDK 17+
- Android SDK Platform 36、Build-Tools 36
- 网络可访问 Maven（项目已配置 Aliyun 镜像作为优先源）

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开根目录构建。

> 本项目使用 AGP 8.7.3 + Gradle 8.9 + compileSdk 36。`io.github.libxposed:api:102.0.0` 以 `compileOnly` 引入，运行时由 Vector 提供；`service` 客户端会打包进 APK，用于 UI 与框架通信。API 102 AAR 的元数据声明 `minCompileSdk=37`，因此工程暂时通过 `android.experimental.disableCompileSdkChecks=true` 在 SDK 36 上构建；升级到 SDK 37 后可移除该开关。

## 安装与启用

1. 安装构建出的 APK。
2. 打开 **Vector 管理器 → 模块**，启用 `FnCalls`。
3. 在模块作用域中勾选要分析的目标 App（Vector 的 Scope）。FnCalls 会自动读取该作用域，不需要在模块内再次选择。
4. 打开 FnCalls，等待顶部状态显示「Xposed 服务已连接」。如果一直显示「正在连接 Xposed 服务…」，确认 Vector 已启用模块后，杀掉 FnCalls 重新打开一次（远程偏好由 `io.github.libxposed:service` 提供）。
5. 点击「开始记录」。它会保存当前设置并启动目标 App；之后随时可以点「停止记录」暂停写入。
6. 切换到底部「日志」页查看调用记录，可搜索、按类型过滤，并导出或分享。

> 开始 / 停止与记录开关即时生效。修改规则或记录选项（如参数、深度、方法上限）后，需要强制停止并重新启动目标 App 才会被读取。

Vector 的模块日志标签为 `FnCalls`，可在 Vector 管理器日志中看到注入摘要与错误信息。

## 规则语法

规则分为两组，主页分别显示「N 条规则」/「N 条内部规则」摘要，点击旁边的「编辑规则」进入独立编辑页，点击「保存规则」提交。

系统 API 规则每行一条：

```text
类名#方法1,方法2,方法3
类名#*
```

- `*`、`?` 是方法名通配符；`*` 表示该类的全部声明方法。
- `<init>` 表示构造方法，例如 `android.content.Intent#<init>`。
- 行首 `#` 或 `//` 表示注释。
- 完全限定类名必须准确；找不到类时会写入框架日志和调用日志。
- 默认不 Hook `toString/hashCode/equals/clone/finalize`（使用 `*` 时），避免格式化参数时递归和噪声。

示例：

```text
java.io.File#*
java.net.URL#openConnection,openStream
android.app.Activity#onCreate,onResume,onPause,startActivity
java.lang.Runtime#exec,loadLibrary
android.telephony.TelephonyManager#getDeviceId,getImei,getSubscriberId
```

「添加常用预设」可以一次追加多组规则，之后仍可手动编辑。预设只在系统规则编辑页显示。

APP 内部规则使用同样的 `类名#方法` 格式，但类名支持 `*` 通配：

```text
com.example.MainActivity#onCreate,onResume
com.example.ui.*#*
com.example.*#getViewModel
```

- APP 内部规则为空时保持自动模式：Hook 作用域类中除 synthetic/对象方法外的全部方法（受每类方法数上限约束）。
- 配置 APP 内部规则后，只 Hook 同时匹配类名和方法名的成员。
- 类名前缀字段只在内部规则为空（自动模式）时用于额外发现不在作用域包名下的类；配置内部规则后只匹配规则中的类。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| 记录状态 | 开 | 由「开始记录 / 停止记录」切换。停止后运行中的进程即时停止写入日志；Hook 保留，停止时只做放行，不格式化、不写入 |
| Vector 作用域 | 自动 | 只读展示从 `XposedService.getScope()` 读取的包名；为空时模块不注入 |
| 同时记录子进程 | 关 | 例如 `:push`、`:remote` |
| 记录 APP 内部方法 | 开 | 按包名前缀自动发现类；可配合内部规则精确过滤 |
| APP 内部规则 | 空 | 空表示自动 Hook 作用域类的全部方法；否则按 `类名#方法` 过滤 |
| 内部类名前缀 | 空 | 在作用域包名基础上额外添加的类名前缀；可加 `com.example.lib` 等 |
| 启动时扫描 APK | 关 | 实验性，补获启动前加载的类，会拖慢冷启动 |
| 每类最多 Hook 方法数 | 60 | 控制单类 Hook 规模 |
| 记录构造方法 | 关 | 仅影响自动内部追踪；规则中显式 `<init>` 始终生效 |
| 记录系统 API | 开 | 关闭后仅保留内部方法追踪 |
| 记录参数 | 开 | 参数格式化为单行、截断 |
| 记录返回值/耗时 | 开 | 显示 `← 方法 = 返回值 (耗时)` |
| 记录调用栈 | 关 | 每个事件附带调用方栈帧，开销较大 |
| 调用栈深度 | 6 | 记录栈帧数量 |
| 最大调用深度 | 10 | `0` 表示不限制；深度之外仍执行 Hook 但不记录 |
| 写入 Vector/Xposed 日志 | 关 | 逐条写框架日志，适合排查日志通道问题 |
| 单个日志文件上限 | 8 MB | 超过后轮转为 `.log.1` |

代码层面还有两个保护：App 内部方法总计最多 6000 个，系统 API 总计最多 3000 个，达到上限后会停止新增 Hook 并写一条告警，避免把目标 App 拖死。

## 日志格式

```text
=== FnCalls 1.0.0 | pkg=com.example | proc=com.example | pid=12345 | sdk=36 | api=102 | app=com.example.App ===
[21:02:11.123] [main] D0 → com.example.MainActivity.onCreate(Bundle@1a2b) on MainActivity@3c4d
[21:02:11.124] [main] D1   → android.app.Activity.setContentView(int=2131230816) on MainActivity@3c4d
[21:02:11.125] [main] D1   ← android.app.Activity.setContentView = void (1.21ms)
[21:02:11.180] [main] D0 ← com.example.MainActivity.onCreate = void (57.30ms)
```

- `D<n>`：当前线程的 Hook 调用深度。
- `→`：方法进入；`←`：方法返回。
- 异常：`← 方法 !! java.lang.IllegalStateException: ...`。
- 每个进程启动时写入一条 `===` banner，包含包名、进程名、PID、SDK、libxposed API 版本。
- 日志按**应用包名**分文件，同一应用的主进程与所有子进程写入同一个文件；每行 banner 和事件仍可通过进程名/线程名区分：`/data/data/moe.huzerovo.fncalls/files/traces/<package>.log`（需要 root 才能直接访问，普通用户通过 UI 查看）。

## 目录结构

```text
app/src/main/
├── AndroidManifest.xml             # FnCallsApp / TraceReceiver / FileProvider
├── resources/META-INF/xposed/
│   ├── java_init.list              # 现代 libxposed Java 入口
│   └── module.prop                 # minApiVersion / targetApiVersion
├── java/moe/huzerovo/fncalls/
│   ├── FnCallsModule.java          # XposedModule 入口（onPackageReady）
│   ├── FnCallsApp.java             # UI 侧 XposedService 客户端
│   ├── config/Config.java          # 配置快照、目标匹配、序列化
│   ├── config/Presets.java         # 系统 API 预设规则
│   ├── hook/HookSpec.java          # Class#method 规则解析/通配匹配
│   ├── hook/HookConfig.java        # 在 Hook 进程内读取远程偏好
│   ├── hook/HookLog.java           # 现代 libxposed 日志适配
│   ├── hook/Tracer.java            # Hook 安装、类加载监听、调用树记录
│   ├── hook/ValueFormatter.java    # 参数/返回值/栈帧安全格式化
│   ├── log/TraceSink.java          # 目标进程日志缓冲与批量上报
│   ├── log/TraceReceiver.java      # 显式广播日志接收器
│   ├── log/TraceFiles.java         # 日志文件读写/轮转/尾部读取
│   ├── ui/MainActivity.java        # 底部导航宿主（控制 / 日志两个页面）
│   ├── ui/ControlFragment.java     # 开始/停止、作用域、记录设置
│   ├── ui/LogFragment.java         # 日志浏览、搜索、分类过滤、导出/分享/清空
│   ├── ui/RulesActivity.java       # 系统/内部规则独立编辑页
│   └── ui/UiInsets.java            # 状态栏/导航栏 inset
└── res/                            # Material 3 布局与资源
```

## 限制与注意事项

- 只追踪 **Java/Kotlin 方法**。Native/JNI 函数、ART 内部函数、inline 后的代码不在范围内；需要 native 追踪请使用 Frida、unidbg 等工具。
- 系统 API 的 Hook 在**目标 App 进程**中生效，不会修改 system_server；因此只记录该 App 发起的调用。`android` 包与 `system_server` 进程会被显式跳过，避免把系统进程拖垮。
- 部分隐藏 API（如 `com.android.okhttp.internal.*`、`android.app.SharedPreferencesImpl`）在不同 ROM 上可能不存在，规则会记录一条「找不到规则类」并继续。
- 自动内部追踪使用 `ClassLoader.loadClass`。不 Hook `Class.forName/findClass`，因为它们在部分 ROM（尤其是 `androidx.startup` Provider 初始化阶段）会破坏 ART 的类解析并导致目标 App 启动崩溃。系统 API 规则在 App 启动完成后由后台线程安装，避免大量 Hook 占用主线程导致启动 ANR。需要更完整覆盖时开启「启动时扫描 APK」，或给关键类补充 APP 内部规则。
- Hook 大量方法会显著增加 CPU/内存开销并影响 JIT，请先用小范围规则验证，再逐步扩大。生产环境下建议关闭逐条框架日志、调低最大深度和每类方法数。
- 日志通道依赖模块 App 的 `TraceReceiver`。请不要在记录期间卸载或强制停止 FnCalls；如果接收器不可用，调用日志会丢失，可开启「写入 Vector/Xposed 日志」作为兜底。
- `TraceReceiver` 对同设备上的其他应用可见（仅接受显式 append 广播）。它是一个调试工具，不建议在不受信任的环境中长期启用；日志可能包含路径、参数等敏感信息。
- Vector 作用域是唯一的注入范围来源；修改作用域后重新打开 FnCalls 页面可刷新展示，重启目标 App 后生效。
- 本项目是调用记录器，不是通用脱壳/反调试工具；请只在你有权分析的 App 和设备上使用。

## License

本项目仅用于安全研究与调试。Vector 本身采用 GPL-3.0；在分发本项目时请遵守所依赖组件的许可证。
