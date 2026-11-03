# USB 共享助手

USB 共享助手通过本地前台服务与无障碍服务，检查系统 USB 共享状态，并在明确允许的条件下尝试恢复共享。无需 Root、Shizuku 或电脑常驻。

仅适配 **HUAWEI WLZ-AN00，Android 12 / API 31，系统构建 WLZ-AN00 4.2.0.121(C00E121R3P3)**。不承诺其他机型或系统版本兼容。

[下载 v1.1.7 APK](https://github.com/Chenyi-1999/gpt-auto-open-net/releases/download/v1.1.7/usb-share-guard-1.1.7.apk) · [SHA-256](https://github.com/Chenyi-1999/gpt-auto-open-net/releases/download/v1.1.7/usb-share-guard-1.1.7.apk.sha256) · [公开仓库](https://github.com/Chenyi-1999/gpt-auto-open-net) · [安装与设置](docs/SETUP.md) · [1.1.7 说明](docs/RELEASE_1.1.7.md) · [测试报告](docs/TEST_REPORT.md)

## 使用方式

1. 安装经过校验的 APK，由用户亲自启用“USB共享辅助服务”，允许应用通知及守护通知类别。
2. 在 App 内明确启动守护。守护默认关闭，通过持续通知展示状态。
3. 如需在真实重启后尝试启动，另行启用默认关闭的“开机自动守护”。
4. 使用首页或通知中的“停止守护”结束运行。显式停止同时关闭开机自动守护；之后需由用户重新启用。

安全锁、授权提示及后台策略均由用户手动处理。App 不输入密码、不绕过锁屏或安全机制，不自动确认系统授权或选择 USB 模式。系统及厂商策略可能终止服务，不保证永久运行。

## 隐私和验证边界

不包含广告、云端服务或运行时第三方服务。日志仅保存在手机本地，可由用户查看、清空或导出。无障碍服务仅检查指定的系统 USB 共享流程，不采集其他应用页面，不创建悬浮窗口。USB 调试只用于开发安装和调试。

1.1.7 的 versionCode 19 尚未安装到手机。此前 v18 完成两次独立的受控息屏重启恢复；转换器断电重连 6/6 自动开启是用户回报，未独立核验。新版一小时运行与 Windows 完整联网仍未验证。手机共享开启不等于电脑联网成功。

## 构建

需要 JDK 21、Android SDK 36 和 Build Tools 35.0.0。工程使用 AGP 8.13.2、Gradle 8.13，Java 源码目标 11。

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Release 配置使用构建机器的本地开发证书，且不可调试。自行构建通常使用不同证书，无法保证覆盖既有安装。维护者发布必须在私有环境中使用原证书并核验；密钥和 keystore 不随源码分发。

本公开快照生成于 2026-11-03T08:26:14+08:00（Asia/Shanghai）。64 个应用、测试及构建文件保留原始字节。公开版构建、179 项单元测试、Lint 和签名核验已完成，见[构建摘要](docs/verification/build-summary-1.1.7.json)和[测试报告](docs/TEST_REPORT.md)。
