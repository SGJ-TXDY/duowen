# 多问

**多问是一款用于刷题、复习的 AI 辅助学习软件。**

在 Android 手机上按专题练习，用错题本和间隔复习回顾薄弱知识点，也可以把易忘的内容整理成 Markdown 速记。刷题、模拟考试、速记和学习记录可离线使用，无需注册账号。遇到疑问时，可以主动打开 AI 助手，请它解释题目、分析代码或帮你读笔记。

目前内置题库以嵌入式面试为主，已支持导入符合格式的自定义 JSON 题库。后续会完善自定义题库的使用体验，让学习内容由题库决定，有题库就能用多问学习自己关心的主题。

[下载 1.6.1 APK](https://github.com/SGJ-TXDY/duowen/releases/tag/v1.6.1) · [查看 1.7.5 源码](https://github.com/SGJ-TXDY/duowen/tree/v1.7.5) · [版本记录](https://github.com/SGJ-TXDY/duowen/blob/v1.7.5/README.md)

## 当前功能

- **刷题和模拟考试** · 按知识点、题型或来源筛选。已核对的客观题可自动判分，其他题目可对照参考自评。模拟考试支持选题量和限时作答。
- **错题与间隔复习** · 错误和不会的题目进入错题本，复习计划根据作答结果安排下次回顾。
- **编程练习与 Markdown 速记** · 写代码、保存草稿、记录自己的解法。速记可直接编辑，也可导入 Markdown 和配套图片。
- **自定义题库** · 可导入符合格式的 JSON 题库。[查看题库格式示例](tools/sample_bank.json)。
- **可选 AI 助手** · 可用自己的 API Key 配置 DeepSeek 或自定义 HTTPS 接口。主动提问时，应用会将问题及相关上下文发送至所选服务。Key 加密保存在设备上。

## 安装与构建

支持 **Android 8.0（API 26）及以上**。GitHub Releases 提供 **1.6.1** 安装包，**1.7.5** 可从源码构建。升级前请分别导出学习备份和速记备份，覆盖安装需要相同的应用签名。

项目使用 Kotlin、Jetpack Compose 和 Room。配置 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0 后，在 Android Studio 中打开项目，或从仓库根目录运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS 或 Linux 使用 `sh gradlew assembleDebug testDebugUnitTest lintDebug`。构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

[依赖说明](DEPENDENCIES.md) · [验证记录](VALIDATION.md)
