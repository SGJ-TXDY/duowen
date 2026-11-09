# Lucide 图标来源

应用内图标来自 [Lucide](https://lucide.dev/icons/) 官方 SVG（npm 包 `lucide-static@1.69.0` 的 `icons/*.svg`），
由 `tools/lucide_icons.py` 转换为本地 Compose `ImageVector`（`AppIcons.kt`），运行时不访问网络、不引入图标字体或依赖包。
path、circle、rect、line 等元素全部转换为路径；保持 24 单位视口、2 单位描边、圆端点与圆连接。

许可：ISC（Lucide），其中源自 Feather 的图标另受 MIT 许可，全文见 `LUCIDE_LICENSE.txt`，随 APK 附于 `assets/licenses/`。

重新生成：下载同版本 SVG 到任意目录后运行 `python -I tools/lucide_icons.py <目录>`。

| 接口 | Lucide 图标 | 用途 |
| --- | --- | --- |
| `AppIcons.Home` | [house](https://lucide.dev/icons/house) | 底栏 首页 |
| `AppIcons.Exam` | [clipboard-list](https://lucide.dev/icons/clipboard-list) | 底栏 模拟考试 |
| `AppIcons.QuickNotes` | [book-open](https://lucide.dev/icons/book-open) | 底栏 速记、速记文档 |
| `AppIcons.Mistakes` | [circle-alert](https://lucide.dev/icons/circle-alert) | 底栏 错题 |
| `AppIcons.More` | [layout-grid](https://lucide.dev/icons/layout-grid) | 底栏 更多 |
| `AppIcons.Review` | [refresh-cw](https://lucide.dev/icons/refresh-cw) | 首页 复习入口 |
| `AppIcons.Sync` | [refresh-cw](https://lucide.dev/icons/refresh-cw) | 更多 智能复习 |
| `AppIcons.NotebookPen` | [notebook-pen](https://lucide.dev/icons/notebook-pen) | 更多 全局标签 / 便签 |
| `AppIcons.ClipboardCheck` | [clipboard-check](https://lucide.dev/icons/clipboard-check) | 更多 待核对工作台 |
| `AppIcons.BarChart` | [chart-no-axes-combined](https://lucide.dev/icons/chart-no-axes-combined) | 更多 历史统计 |
| `AppIcons.Coin` | [coins](https://lucide.dev/icons/coins) | 更多 AI 费用估算 |
| `AppIcons.Settings` | [settings](https://lucide.dev/icons/settings) | 更多 设置 |
| `AppIcons.Back` | [arrow-left](https://lucide.dev/icons/arrow-left) | 顶栏返回 |
| `AppIcons.Practice` | [pencil-line](https://lucide.dev/icons/pencil-line) | 练习（保留接口） |
| `AppIcons.ChevronLeft` | [chevron-left](https://lucide.dev/icons/chevron-left) | 上一题 |
| `AppIcons.ChevronRight` | [chevron-right](https://lucide.dev/icons/chevron-right) | 列表行、下一题 |
| `AppIcons.ChevronDown` | [chevron-down](https://lucide.dev/icons/chevron-down) | 展开 |
| `AppIcons.ChevronUp` | [chevron-up](https://lucide.dev/icons/chevron-up) | 收起 |
| `AppIcons.Note` | [sticky-note](https://lucide.dev/icons/sticky-note) | 题目笔记 |
| `AppIcons.Star` | [star](https://lucide.dev/icons/star) | 收藏 |
| `AppIcons.StarFilled` | [star](https://lucide.dev/icons/star) | 已收藏（实心） |
| `AppIcons.MoreVert` | [ellipsis-vertical](https://lucide.dev/icons/ellipsis-vertical) | 溢出菜单 |
| `AppIcons.Check` | [check](https://lucide.dev/icons/check) | 选中、正确 |
| `AppIcons.Close` | [x](https://lucide.dev/icons/x) | 关闭、错误 |
| `AppIcons.Help` | [circle-question-mark](https://lucide.dev/icons/circle-question-mark) | 不会 |
| `AppIcons.Chat` | [message-square](https://lucide.dev/icons/message-square) | AI 讨论 |
| `AppIcons.Pencil` | [pencil](https://lucide.dev/icons/pencil) | 编辑 |
| `AppIcons.Tag` | [tag](https://lucide.dev/icons/tag) | 标签（保留接口） |
| `AppIcons.Info` | [info](https://lucide.dev/icons/info) | 提示 |
| `AppIcons.Clock` | [clock](https://lucide.dev/icons/clock) | 计时 |
| `AppIcons.Send` | [send-horizontal](https://lucide.dev/icons/send-horizontal) | 发送追问 |
| `AppIcons.Code` | [code](https://lucide.dev/icons/code) | 编程专区 |
| `AppIcons.Flag` | [flag](https://lucide.dev/icons/flag) | 标记 |
| `AppIcons.Search` | [search](https://lucide.dev/icons/search) | 搜索 |
| `AppIcons.Play` | [play](https://lucide.dev/icons/play) | 开始（实心） |
| `AppIcons.Shuffle` | [shuffle](https://lucide.dev/icons/shuffle) | 随机练习 |
| `AppIcons.ListOrder` | [list](https://lucide.dev/icons/list) | 顺序练习、速记目录 |
| `AppIcons.Target` | [target](https://lucide.dev/icons/target) | 薄弱点练习 |
| `AppIcons.Book` | [book](https://lucide.dev/icons/book) | 题库、阅读 |
| `AppIcons.Folder` | [folder](https://lucide.dev/icons/folder) | 分类练习 |
| `AppIcons.Trash` | [trash-2](https://lucide.dev/icons/trash-2) | 删除 |
| `AppIcons.Import` | [download](https://lucide.dev/icons/download) | 导入 |
| `AppIcons.Person` | [user](https://lucide.dev/icons/user) | 头像占位 |
| `AppIcons.Flame` | [flame](https://lucide.dev/icons/flame) | 连续学习 |
| `AppIcons.Plus` | [plus](https://lucide.dev/icons/plus) | AI 讨论 来源分组新建 |
| `AppIcons.SquarePen` | [square-pen](https://lucide.dev/icons/square-pen) | AI 讨论 新建自由对话 |
| `AppIcons.FileText` | [file-text](https://lucide.dev/icons/file-text) | AI 讨论 题目来源 |
| `AppIcons.MessagesSquare` | [messages-square](https://lucide.dev/icons/messages-square) | AI 讨论 自由对话 |
| `AppIcons.Sparkles` | [sparkles](https://lucide.dev/icons/sparkles) | AI 讨论 临时问 AI |
| `AppIcons.ArrowUpRight` | [arrow-up-right](https://lucide.dev/icons/arrow-up-right) | AI 讨论 打开来源 |
| `AppIcons.Sun` | [sun](https://lucide.dev/icons/sun) | 设置 外观 |
| `AppIcons.Bot` | [bot](https://lucide.dev/icons/bot) | 设置 AI 模型 |
| `AppIcons.Bell` | [bell](https://lucide.dev/icons/bell) | 设置 复习提醒 |
| `AppIcons.Database` | [database](https://lucide.dev/icons/database) | 设置 题库与备份 |
| `AppIcons.Stethoscope` | [stethoscope](https://lucide.dev/icons/stethoscope) | 设置 诊断 |
| `AppIcons.Pin` | [pin](https://lucide.dev/icons/pin) | 速记 置顶 |
| `AppIcons.KeyRound` | [key-round](https://lucide.dev/icons/key-round) | 设置 API Key |
| `AppIcons.Palette` | [palette](https://lucide.dev/icons/palette) | 设置 主题色 |
| `AppIcons.Type` | [type](https://lucide.dev/icons/type) | 设置 字号 |
| `AppIcons.Heading` | [heading](https://lucide.dev/icons/heading) | 速记编辑 标题 |
| `AppIcons.ListChecks` | [list-checks](https://lucide.dev/icons/list-checks) | 速记编辑 待办 |
| `AppIcons.Pilcrow` | [pilcrow](https://lucide.dev/icons/pilcrow) | 速记编辑 段落 |
| `AppIcons.Eye` | [eye](https://lucide.dev/icons/eye) | 速记编辑 预览 |
| `AppIcons.Upload` | [upload](https://lucide.dev/icons/upload) | 导出备份 |
