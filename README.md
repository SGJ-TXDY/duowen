# 芯题本 · EmbeddedQuiz

**面向嵌入式面试备考的 Android 复习工具。** 按专题刷题，用错题本和间隔复习回顾薄弱知识点，再把易忘的知识整理成 Markdown 速记。刷题、考试、速记和学习记录都可以离线使用，无需注册账号。

[下载 APK](https://github.com/SGJ-TXDY/embeddedquiz/releases/tag/v1.6.1) · [查看 v1.7.5 源码](https://github.com/SGJ-TXDY/embeddedquiz/tree/v1.7.5) · [版本记录](https://github.com/SGJ-TXDY/embeddedquiz/blob/v1.7.5/README.md#175--速记编辑与-ai-模型设置)

## 能做什么

| 功能 | 使用方式 |
| --- | --- |
| **专题刷题** | 从嵌入式基础、Linux 与驱动、数据结构与算法等内容入手，按知识点、题型或来源筛选。已核对的客观题可自动判分，其他题目可对照参考自评。 |
| **错题与复习** | 错误和不会的题目进入错题本，次日可复习。答对的题目按 SM-2 变体安排下一次复习。 |
| **模拟考试** | 从有可靠标准答案的客观题中抽题，支持选题量、限时作答和交卷后统一判分。 |
| **编程练习** | 在独立的编程专区写代码、存草稿、记录自己的解法。代码可复制到开发环境运行。 |
| **速记与便签** | 写 Markdown 速记，或导入 Markdown 和含图片的 ZIP。支持目录、搜索、置顶和阅读位置记忆，也可以用便签记录随手想到的内容。 |
| **可选 AI 助手** | 使用自己的 API Key 配置 DeepSeek 或自定义 HTTPS 接口，对题目、代码或速记提问，并回看讨论。 |

v1.7.5 随包题库包含 **906 条练习**，其中 **79 条**归入编程专区，**128 道**客观题可自动判分。题库汇集多份整理材料和原创练习，部分答案保留「待核对」标记。可导入自己的 JSON 题库，[查看示例](tools/sample_bank.json)和[来源审计](source-audit.json)。

## 安装与升级

- **Android 8.0（API 26）及以上。** 当前 Releases 提供 [1.6.1 APK](https://github.com/SGJ-TXDY/embeddedquiz/releases/tag/v1.6.1)。需要 **1.7.5** 的速记编辑和 AI 模型设置时，可从对应源码标签自行构建。
- **从旧版升级前**，分别导出学习备份和速记备份。覆盖安装需要相同的应用签名，自行构建的 Debug APK 通常使用本机签名。遇到签名不一致时，请先保留旧应用和备份。
- **使用 AI 时**，应用会将本次问题及相关上下文发送到所选服务。API Key 加密保存在设备上，手动备份不包含 Key。

## 从源码构建

项目使用 **Kotlin、Jetpack Compose（Material 3）和 Room**。安装 **JDK 17**、**Android SDK Platform 35** 和 **Build Tools 35.0.0**，用 Android Studio 打开项目，或在配置好 SDK 后从仓库根目录运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

在 macOS 或 Linux 上运行 `sh gradlew assembleDebug testDebugUnitTest lintDebug`。首次构建需要下载依赖。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

[依赖说明](DEPENDENCIES.md) · [验证记录](VALIDATION.md) · [反馈问题](https://github.com/SGJ-TXDY/embeddedquiz/issues/new)
