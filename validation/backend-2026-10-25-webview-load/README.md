# 速记 WebView 正文加载失败：待接入最小修复

当前状态：用户明确授权本次最小修复后，已将验证通过的补丁接入工作区，仅改 WebView 加载与路由，不改 Compose 布局。新试装包单独输出，旧 APK 不覆盖。

- QuickNoteReader-fix.patch：可审阅的最小补丁，只改 ReaderWeb.load 与 ReaderClient 请求处理，不改 Compose / CSS / 数据。
- proposed/：完整拟修改文件与新增路由回归测试，已按授权复制到当前 app/src。
- results/、summary.json：17 项相关测试通过（新请求测试 API 28 / 33 共 12 项，原速记 UI 5 项）。
- proposed-tests.log：首轮检查暴露正文响应状态未显式设置；transport-tests.log 是改为明确 200 后通过的路由验证。
- before-state.json：前端、现有测试、构建 / 规则、APK 与 Git 状态指纹，除授权的 QuickNoteReader.kt 加载部分外均核对不变。

现有已导入 Markdown 不需要删除或重新导入。没有提交、推送或覆盖旧 APK。新增独立 webfix 试装包路径 / 哈希在 summary.json；签名已与原速记包核对一致。没有真实 Android WebView 设备验证，Robolectric 只验证请求路由和 UI 框架。

用户后续反馈“修的好”并授权整理交接、最终 APK 及提交。最新复盘见两份交接文档第五轮；finalApk / 哈希见 summary.json，APK 不进入 Git 源码历史。

归档提交时仅清除日志行尾空白。HEAD / index 等保护检查记录验证时点；之后用户已另行授权整合提交与推送，最新状态见交接文档第五轮。
