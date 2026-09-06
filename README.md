# Edge Secure DNS（恢复 Edge「使用安全 DNS」设置入口）

一个 Xposed 模块（现代 libxposed API），恢复 Microsoft Edge 安卓版（Chromium 内核）设置中
被隐藏的「隐私与安全 → 使用安全 DNS」入口。

- 作用域：`com.microsoft.emmx.beta`（Edge Beta）、`com.microsoft.emmx`（Edge 稳定版）、
  `com.microsoft.emmx.canary`（Edge Canary）
- 适配基准：Edge Beta **153.0.4234.18**（versionCode 423401823）、
  Edge Canary **154.0.4257.0**（versionCode 425700005，均已实测 APK 结构）
- 运行要求：Edge 自身完整保留了安全 DNS 设置页与其多语言文案，模块只负责把入口加回列表，
  不改任何系统行为、不写入 Edge 数据。

## 框架要求

模块基于**现代 Xposed API（libxposed API 102，`META-INF/xposed/java_init.list` 入口）**编写，
需要支持该 API 的 Xposed 框架（`module.prop` 中 `minApiVersion=101`）。
仅支持经典 `assets/xposed_init` 入口的旧框架（及依赖经典 API 的 LSPatch/NPatch 嵌入方案）
无法加载本模块。另外，现代 API 的包级回调按 Android 10（Q）+ 设计，实际生效以框架支持的
系统版本为准。

## 安装使用

1. 安装模块 APK。
2. 打开 Xposed 管理器 → 模块 → 启用「Edge Secure DNS」（作用域已通过 `scope.list` 静态声明为
   Edge Beta / Edge 稳定版，无需手动勾选）。
3. 强制停止 Edge 后重新打开：设置 → 隐私、搜索和服务 → 隐私与安全 分区底部出现「使用安全的 DNS」。
4. 点进去即为 Chromium 原生的安全 DNS 页（关闭 / 自动 / 手动选择供应商）。

## 构建

- 命令行构建需要 **JDK 17+**（Xposed API 构件为 Java 17 字节码）与 Android SDK：
  将 `local.properties.example` 复制为 `local.properties` 并按实际路径修改，
  再以 `JAVA_HOME=<jdk-17+> ./gradlew assembleRelease` 构建。
- CI（GitHub Actions）已使用 JDK 17，无需额外配置。

## 工作原理

Edge 的真实隐私页是 `org.chromium.chrome.browser.edge_settings.EdgePrivacySettings`，
它加载的 Edge 专属 XML（edge_privacy_preferences_v2）里没有 secure_dns 条目；而完整的安全
DNS 设置页（`org.chromium.chrome.browser.privacy.secure_dns.SecureDnsSettings`）及其多语言
文案仍随 APK 发布。模块在其 `onCreatePreferences` 结束后动态构造一个「使用安全的 DNS」偏好
项（复用 Edge 自带的字符串与样式），点击即启动原生安全 DNS 设置页。

Edge 安装了自定义 AppComponentFactory，其类加载器在经典 API 的 `handleLoadPackage` 时机还
不可见；现代 API 的 `onPackageReady` 回调在 AppComponentFactory 实例化类加载器之后触发，
正好拿到 Edge 的类加载器。模块另以 `Activity.onCreate` 兜底重试，覆盖隐私页类加载时机异常
晚的版本。
