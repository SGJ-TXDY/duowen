# 速记后端验证记录（2026-10-25）

最终结果：37 项相关测试全部通过，新增 18 项。只运行指定业务 / 渲染 / 存储测试类及两个提醒方法，未运行完整套件或 Compose UI 测试，未打包 / 签名 APK。

- `summary.json`、`results/`：最终通过汇总与各类最新通过的 JUnit XML。
- `actual-document-audit.json`：用户原文 SHA-256 与实际语法审计（7 标题、5 表格、1 代码块）。
- `initial-tests.log`：初次编译发现字符转义问题。
- `table-and-test-wait.log`、`test-wait-diagnostic.txt`：表格容器注册顺序与新测试主线程等待的诊断，随后修正。
- `final-tests.log`：相关 36 项中 35 项通过，剩余为新 Robolectric 内容流测试适配问题。
- `content-uri-tests.log`：修正模拟流后，仅重跑隔离类 4 项，全过。
- `heading-boundary-tests.log`：补齐超长标题锚点边界，仅重跑渲染类 6 项，全过。
- `reference-html-check/`：独立测试外壳的 3 个浏览器验证场景（HTML、参考截图、几何检查），不是产品 UI 或真机验收。使用实际后端 HTML，测试 CSS 不打进 APK。
- `android-dependency-check.log`：新增离线 JAR 的 Android DEX 转换和 Java 资源合并通过，未生成安装包。
- `before-state.json`：开发前保护指纹。所有 Compose、UI 测试、前端交接、原文、schema、旧 APK、Git HEAD / index 均未改变。

只变更后端与离线解析器依赖声明；产品列表 / 阅读容器 / 底部导航待 Claude 接入，详细见 docs/BACKEND_HANDOFF.md 和 docs/QUICK_NOTES_RENDERING.md。

归档提交时仅清除日志行尾空白。HEAD / index 等保护检查记录验证时点；之后用户已另行授权整合提交与推送，最新状态见交接文档第五轮。
