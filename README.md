# Edge Secure DNS（恢复 Edge「使用安全 DNS」设置入口）

一个 Xposed 模块（现代 libxposed API），恢复 Microsoft Edge 安卓版（Chromium 内核）设置中
被隐藏的「隐私与安全 → 使用安全 DNS」入口，并在 Edge **Beta/Stable** 上补充 Canary 才有的
「加载本地扩展(.crx)」开发者入口。

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
   点进去即为 Chromium 原生的安全 DNS 页（关闭 / 自动 / 手动选择供应商）。

### 加载本地扩展（仅 Beta / Stable）

在 Edge **稳定版 / Beta** 上，设置 → 关于（连点版本号 7 次开启开发者选项）→ 开发者选项页底部会
出现一条「加载本地扩展」。点击后用系统文件选择器选取一个 `.crx` 扩展文件，即可走 Edge 内置的
安装流程安装（Canary 本身已带原生入口，故不注入）。
