# 依赖升级决策

日期：2026-10-23。依赖改动单独提交；保留 AGP 8.7.3、Kotlin 2.0.21、配套 KSP 2.0.21-1.0.28、compileSdk 35 与 minSdk 26。

|组件|1.3.0 基线|本次固定版本|
|---|---|---|
|Compose BOM|2024.12.01|2025.01.01|
|Activity|1.9.3|1.10.1|
|Lifecycle|2.8.7|2.8.7（保留）|
|Room / KSP 编译器|2.6.1|2.7.2|
|WorkManager|无|2.9.1（新增本地提醒）|

选择与现有 Kotlin 2.0 / SDK 35 工具链相配的固定版本，不把整套构建工具升级混入功能改动。Room 2.7 系列官方要求 Kotlin 2.0，项目满足这一点；最终以 AAR 元数据检查、实际编译、迁移及回归测试为准，不跳过元数据检查或提高允许的元数据版本。

未追随最新整套版本：当前官方 Lifecycle 2.11 文档要求的 Compose/AGP 版本已超出此项目工具链（提及最低 AGP 9.2）；最新 Activity/Compose 系列同样正在提高 compileSdk 要求。为保持可复现构建和覆盖安装，本次不迁移到这些版本。也不为获得最新 Room 2.8 而单独扩大 Kotlin/KSP 组合的升级范围。

WorkManager 使用普通延迟后台任务，不申请精确闹钟权限。按用户硬性限制，从合并清单移除 WorkManager 的额外权限与前台服务，新增运行时权限仅 POST_NOTIFICATIONS。因此设备重启后需打开应用恢复提醒安排，不能依赖开机广播。

实际兼容性结果：曾尝试 Lifecycle 2.9.4，主代码和 AAR 元数据检查通过，但其传递的 Compose Lint 检测器在 AGP 8.7.3 的 UAST 接口上触发 IncompatibleClassChangeError（FrequentlyChangingValueDetector）。按“不兼容则保留原版本”的要求回退到 2.8.7，没有禁用检测器、跳过 Lint 或升级 AGP 来绕过问题。失败日志保存在 validation/build-1.4.0.log。

官方依据：

- [Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room)
- [Activity 发布说明](https://developer.android.com/jetpack/androidx/releases/activity)
- [Lifecycle 发布说明](https://developer.android.com/jetpack/androidx/releases/lifecycle)
- [Compose BOM 对照](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [WorkManager 发布说明](https://developer.android.com/jetpack/androidx/releases/work)


## 2026-10-25 速记离线 Markdown 引擎

新增 app/libs 下 commonmark、commonmark-ext-gfm-tables、commonmark-ext-gfm-strikethrough，固定为上游 0.24.0，通过 implementation(files(...)) 使用。许可证见 commonmark-LICENSE.txt，官方 Maven 下载地址与 SHA-256 见 commonmark-provenance.json。无需新增运行时网络访问；不修改 Room / 备份版本。更新这些 JAR 时必须同步检查核心和扩展版本一致并重跑 QuickNoteMarkdownTest。
