# Edge Secure DNS（恢复 Edge「使用安全 DNS」设置入口）

一个 Xposed 模块（LSPosed/LSPatch/NPatch 通用），恢复 Microsoft Edge 安卓版（Chromium 内核）设置中
被隐藏的「隐私与安全 → 使用安全 DNS」入口。

- 作用域：`com.microsoft.emmx.beta`（Edge Beta）、`com.microsoft.emmx`（Edge 稳定版）
- 适配基准：Edge Beta **153.0.4234.18**（versionCode 423401823，已实测 APK 结构）
- 运行要求：Edge 自身完整保留了安全 DNS 设置页与其多语言文案，模块只负责把入口加回列表，
  不改任何系统行为、不写入 Edge 数据。

## 安装使用（LSPosed）

1. 安装模块 APK。
2. 打开 LSPosed 管理器 → 模块 → 启用「Edge Secure DNS」。
3. 在模块作用域中勾选 **Edge Beta**（和/或 Edge 稳定版）。
4. 强制停止 Edge 后重新打开：设置 → 隐私、搜索和服务 → 隐私与安全 分区底部出现「使用安全的 DNS」。
5. 点进去即为 Chromium 原生的安全 DNS 页（关闭 / 自动 / 手动选择供应商）。

## 安装使用（LSPatch/NPatch，免 Root，已真机实测）

- 管理器模式：安装 LSPatch/NPatch 管理器与模块 APK → LSPatch/NPatch 里修补目标 Edge 并勾选本模块。

注意：LSPatch/NPatch 嵌入会改变签名，需先卸载原 Edge（数据请自行备份）或使用 Edge beta 版本。修补时务必勾选「注入加载器 Dex」。
