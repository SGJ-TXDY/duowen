# 速记第一版：渲染与前端接入约定

## 实际文档审计

样本为用户的《嵌入式 Linux 面试复习：启动流程、根文件系统与驱动.md》，测试副本在 `app/src/test/resources/quicknotes/linux.md`，原始文件不修改、不自动塞进题库。审计确认 **7 个标题、5 张表格、1 个 C 围栏代码块**，使用中文、粗体、有序 / 无序列表、行内代码、转义下划线、HTML 字符实体和 `<类>/<设备>` 占位文本；没有图片或数学公式。

原文 `[embedded content: platform 驱动…]` 只有占位文字，不含图形本体。后端保留文字并给出警告，不能声称恢复了原图。审计记录与原文 SHA-256 见本轮 validation 中的 `actual-document-audit.json`。

额外语法样例 `syntax.md` 覆盖强调 / 删除线、引用、嵌套列表、分割线、链接、表格、围栏 / 缩进代码、图片和重复标题。数学公式另用测试确认“明确提示未支持”，不宣称公式可排版。

## 解析方案与实际支持范围

固定使用上游 commonmark-java **0.24.0** 核心和 GFM tables / strikethrough 扩展。官方 JAR、BSD 许可证、来源与 SHA-256 放在 `app/libs/`，以本地文件依赖构建，避免本项目离线工具链无法取新包；无新增网络请求或网络权限。

`QuickNoteMarkdown` 生成语义 HTML，而非原始 Markdown：标题 1–6、段落、软 / 硬换行、粗体、强调、删除线、引用、有序 / 无序及嵌套列表、分割线、链接、GFM 表格、行内代码、围栏 / 缩进代码块、HTML 字符实体和 CommonMark 转义。围栏的语言标记不提供语法高亮；代码的缩进和换行保存在 `codeBlocks`，复制不会从 HTML 反向抽取。

标题 ID 按解析后的纯标题生成：保留中英文字母、数字、下划线、空格、连字符；转小写，空白变连字符，基础 ID 最长 2000 字符，重复项加 `-1/-2`，保证可持久化。目录由同一 AST 产生，中文和重复标题的目录与正文 ID 一致。支持 `#fragment` 文档内链接；不存在的锚点会提示。不要假设 ID 与所有第三方编辑器完全相同，前端直接使用 `headings[].id`。

支持 HTTPS / HTTP / mailto 链接（由用户点击后交系统处理），不自动联网。相对的其他 Markdown 文件链接、显式 HTML 锚点、原始 HTML 布局、SVG、网络 / data URI 图片、LaTeX 数学公式、Mermaid、任务勾选框、脚注、YAML 元数据等未实现扩展不在支持承诺内。原始 HTML 按文本转义；Mermaid 围栏按代码显示。实际出现数学公式会给出不支持排版的警告。

## Claude 实现的阅读容器（后端不改 Compose）

后端返回 `htmlBody/headings/codeBlocks/warnings`，不接管产品视觉。Claude 用 Compose 包装的 WebView / 合适 HTML 容器显示正文，不能直接用 `Text(markdown)`。需要实现：

1. HTML 外壳带 UTF-8、移动 viewport，字体 / 背景 / 前景色随现有浅深主题和字号设置；大字号同步 WebView textZoom 或等价机制。不得固定黑字白底。
2. `.qn-table-scroll` 和 `.qn-code-scroll` 为独立的 `overflow-x:auto; max-width:100%` 横向区域。表格允许内容宽于区域；代码 `pre` 使用 `white-space:pre`，不能用 ellipsis、隐藏 overflow 或 trim 缩进。正文允许纵向滚动。
3. `quicknote-copy:code-N` 只作为本地动作，拦截后调用 `vm.quickNotes.copyCode("code-N")`，不要交外部应用或网络加载。后端将对应原文直接写剪贴板，保留空白。
4. 目录按钮用 `headings`；点击定位同一正文元素 `id`。文档内 `#fragment` 保持在本页。未知锚点 / 不支持链接提示在 `warnings`，不可无声吞掉。
5. **必须先区分正文主请求和子资源。** 不能对所有“非图片”请求直接返回 404：Android 的 shouldInterceptRequest 也可能收到 data: 正文加载请求。推荐正文使用固定地址 `https://appassets.androidplatform.net/quicknotes/{id}/document.html`，由内存中的可信 UTF-8 外壳 HTML 响应；仅这个精确地址的主框架请求可以取正文，不放开其他 data/file/content 或其他文档请求。图片才按下一条规则处理。详见 `validation/backend-2026-10-25-webview-load/QuickNoteReader-fix.patch`（是否应用以本轮交接为准）。
6. 图片只允许本页虚拟源 `https://appassets.androidplatform.net/quicknotes/{id}/assets/{encodedPath}`。在后台资源拦截回调调用 `readAsset` 并返回对应 MIME 的响应；未匹配资源返回明确 404，不回退网络或 file://。禁止 file / content 通用文件访问、外部子资源和导入 HTML 的脚本执行。HTTPS / HTTP / mailto 主文档导航交系统处理，其他 scheme 拦截。资源拦截回调处于后台线程时可桥接 suspend 查询，不能在主线程阻塞。
7. 本地图片缺失 / 网络或 SVG 图片会在正文显示明确占位及 `warnings`。还须处理浏览器图片 decode/onerror，将损坏图片替换成可读提示；不能只留下破图标。
8. 代码复制和目录不能依赖导入文档内的 JavaScript。若为采集滚动位置 / 测量标题启用 JavaScript，只运行应用自己生成的受控脚本，保持内容转义并阻断外部资源；不要给导入内容任意原生桥权限。

上述 CSS 和容器行为仍需 Claude 实施并做浅色 / 深色、大字号、小屏和横向滚动验收；本轮的 HTML 语义测试不能代替 Android 阅读页面的视觉验收。

## 图片配套导入

系统选择器首先支持 `.md`（有效 UTF-8，允许 BOM），导入即复制到应用内部，不依赖原 URI 的长期权限。单个 Markdown 无法自动取得相邻文件的权限；不要尝试绕过 SAF 从原目录猜测路径。

有本地相对图片时选择**配套 ZIP**：恰好一个 `.md`，保留它与 PNG / JPEG / GIF / WebP 的相对目录。例：`docs/main.md` 引用 `../assets/示例图.png`，ZIP 中放这两个文件。中文文件名、百分号编码和 `+` 按文件路径处理；路径规范化可在包内使用 `..`，但不能超出包根目录。图片复制到私有资源目录并按内容哈希保存，原始逻辑路径由索引映射。

单文档正文上限 4 MiB，Markdown 加图片上限 32 MiB；ZIP 最多 512 个条目（每份文档图片少于 512）。图片按扩展名和文件头验证，SVG / 其他附带文件不会作为可加载资源；缺失引用在正文提示。无自动下载远程图片、无目录扫描或文档转题功能。

## 阅读位置与页面生命周期

每份文档使用独立 UUID，位置为 `QuickNotePosition(anchor, offset, fraction)`：anchor 是当前标题 ID，offset 是该标题到视窗顶的 CSS 像素相对偏移，fraction 是滚动比例 0..1。无当前标题可用空 anchor。offset 非负且有限，fraction 有限且在 0..1；非空 anchor 必须属于此文档。

前端滚动时节流记录（建议不高于每秒 2 次），先调用 `savePosition` 入队，再在离页 / 退后台时及时 `flushPositions`。保存队列独立于题目笔记 / 全局便签；异常在 `positionSaveError`，可 `retryPositionWrites`，不能忽略。页面打开 / 导入 / 删除等 `working` 期间不接收滚动写入，避免销毁页面的迟到回调覆盖状态；离页前先提交最后位置，再调用 `closeDocument/leave`。

DOM 布局和图片稳定后，先按 anchor + CSS offset 恢复；宽度 / 字号变化、测量失效时以 fraction 为回退，限制在合法滚动范围。保存位置不等同“完成阅读”，不计入每日目标 / 成绩 / SRS。进程被立即杀死时尚未入队或未完成的最后一小段滚动可能丢失，已完成的原子写入重启可恢复。

## 备份边界

Room 题库仍为 4，原刷题全量备份仍为 3，均不改结构。速记放在 `filesDir/quicknotes-v1/` 的独立原子索引、正文和图片文件中，不用题目 / Progress / StickyNote 存储。

原刷题备份**不包含速记**，恢复旧刷题备份不会删除或覆盖速记。速记提供独立 ZIP 备份，`quickNotesVersion=1`，包含全部正文、图片、标题和位置。默认恢复合并并跳过内容完全相同的文档；冲突但内容不同的文档复制成新 UUID / 可区分标题，原文不覆盖。跳过的现有文档保留自己较新的位置；新恢复文档使用备份位置。

只有用户明确确认的 `replace=true` 才替换速记库（不触碰刷题数据）；这也可用有效备份修复损坏的速记索引。恢复先校验所有条目与内容哈希，再原子提交索引；失败保留旧库。独立备份最大 64 MiB、512 个条目、200 份文档，超限明确失败而非截断。请在 UI 区分“导入 Markdown 配套 ZIP”与“恢复速记备份 ZIP”。卸载前未导出的速记数据会随应用私有数据一起移除。

## 参考

- [commonmark-java 官方文档](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0)
- [Android 官方本地 Web 内容加载说明](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
