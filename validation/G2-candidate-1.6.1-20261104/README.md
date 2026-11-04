# G2 / 1.6.1 Debug 验证候选统一交付

日期：2026-11-04（Asia/Tokyo）。按共享文档 §19.2–19.3 单人直接交付；Claude 无需再接任务。开工 HEAD `31ea8b2`，原工作区仅 Architect 的五个未跟踪项，未修改/暂存。当前状态：**候选产物核验完成，用户设备回报 16/16 通过，待 Architect 最终发布裁定**，不是稳定 Release。

## 候选与提交

[候选 APK](../../../EmbeddedQuiz-1.6.1-candidate-debug.apk) 的绝对路径：

`F:\学习\嵌入式学习\知识总结\复习软件\EmbeddedQuiz-1.6.1-candidate-debug.apk`

文件已经从实际新构建输出复制为独立文件，创建时禁止覆盖；原 1.6.0 稳定 APK 的前后 SHA-256 未变。旁边同名 `.apk.sha256` 为校验文件。上方相对链接供本机使用，绝对路径为交付依据。

| 项目 | 实际核验值 |
| --- | --- |
| 构建 Git SHA | `06796d459e330c2ba40790714dd730acb801072b` |
| applicationId | `cn.embeddedquiz` |
| versionName / versionCode | **1.6.1 / 8** |
| min SDK / target SDK | **26 / 35** |
| 构建类型 | **Debug 验证候选**，沿用既有 Debug 身份，不称正式 Release 签名 |
| APK 大小 | **12986835 bytes** |
| APK SHA-256 | `99DDAA886D09AA0E5428D2B8EA2A88147D93B35BE2CFE99DE44ECBBB5714D49D` |
| 证书 SHA-256 | `2c73c905e6d127dd92fbfaf5351058572cb8e78e98beb477eb1a0f37ee43b151` |
| 签名验证 | apksigner 返回 0，验证通过；与实际 1.6.0/7 APK 证书一致 |

实际原始元数据见 candidate-badging.txt、candidate-signature.txt（仅验证摘要/证书 SHA-256，不输出密钥）、candidate-manifest.json；baseline-apk.json、baseline-1.6.0-badging.txt、baseline-1.6.0-signature.txt 核对了既有真实 1.6.0/7 APK，不以文件名推断版本。

## 版本与变更范围

origin 为 `https://github.com/SGJ-TXDY/-app.git`。准备时 git ls-remote 核实远端 main `92d1cd70df874680caef78ca95ccc0a97b6e8b6d`，标签只有 v1.6.0（解引用同该提交），无 v1.6.1。通过连接器读取该确切远端提交的 build.gradle.kts，确认 1.6.0/7；本地历史查无他人已用的 1.6.1/8，现只由本候选提交分配。remote-refs.txt、remote-main-build.gradle.kts.txt、version-history-check.txt 记录核对；没有推送、创建/覆盖标签或 Release。

构建提交仅三文件：app/build.gradle.kts 两个版本字段；README.md 当前候选说明、准确考试/数据说明；新增 CHANGELOG-1.6.1.md。业务代码、测试、依赖、Room5、备份3、applicationId、题库、签名配置均未改。后续交付提交只增加本目录与 BACKEND_HANDOFF，不改变候选的构建来源。

## 验证结果与复用依据

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| README Python unittest discover | **11/11，OK，退出 0** | python-unittest.log、python-exit-code.txt |
| Python 环境 | 3.12.10 / python-docx 1.2.0，使用既有环境，无依赖更改 | python-environment.txt |
| Android 实现回归 | **复用固定 492b361 的 248/248**，此次未重跑全套 | ../T4-integrated-missingfix-20261104/ |
| 源码复用核验 | 109 个 Android 输入：108 完全不变；唯一 build.gradle.kts 还原两字段后与原字节 SHA 一致 | android-reuse-verification.csv |
| 新版本构建 + Lint | **成功，退出 0**，BUILD SUCCESSFUL in 1m 58s；47 个任务（15 执行、32 up-to-date） | candidate-build-lint.log、build-exit-code.txt |
| Lint | **0 错误、5 个既有警告**，ID/说明与已验收版本一致 | lint/ 原始 XML/HTML/TXT |
| 新构建输入前后 | 109 项工作区/隔离副本逐字节一致，期间未改源码 | candidate-source-fingerprints.csv、candidate-after-fingerprints.csv |
| APK 包/版本/SDK/签名/摘要 | 全部从实际新 APK 核对，见上表 | candidate-manifest.json 等 |
| 设备 | 用户自行试装：一加 Ace 2 / ColorOS 16，原版 1.6.0，备份可读取；D01–D16 全部按用户回报通过 | DEVICE_REPORT-20261104.md、DEVICE_RESULTS.json；准备时 adb 无设备记录保留 |

复用 GPT 自有 `.gpt-t3-backend-20261104` 独立 app/build 和 ASCII 入口；JDK17 / Gradle8.9 / AGP8.7.3 / Kotlin2.0.21 / Room2.7.2，沿用仓库 tools/build.ps1 离线构建，没有覆盖其他执行者输出。build-head.txt 固定提交，build-command.txt 与 verification-commands.txt 记录命令。

Python 实际命令：

```powershell
C:\Users\woziji\AppData\Local\Programs\Python\Python312\python.exe -m unittest discover -s tools -p 'test_*.py' -v
```

候选构建实际命令：

```powershell
./tools/build.ps1 -Tasks @('assembleDebug','lintDebug','--continue','--no-daemon')
```

首轮 APK 元数据核验脚本误按 sdkVersion 解析，而当前 aapt2 输出 minSdkVersion；已依据原始输出修正解析并完成核验。APK 实际始终为 1.6.1/8、min26/target35，没有修改应用或重编来绕过，未在失败断言之前生成候选。记录见 apk-verification-notes.txt。

## 用户设备操作与记录

请先读 [统一 7 步清单](DEVICE_CHECKLIST.md)，升级前导出并确认学习备份可读取、速记另备份，保留原 APK；传候选到手机后覆盖安装，不卸载/清数据。检查涵盖覆盖数据保留、考试/放弃/重启、冷/热通知、长代码/输入法/快速离页、后台恢复、备份/恢复/重置、手动笔记、诊断与大字号/TalkBack。

用户于 2026-11-04 回报“一加Ace2，cokorOS16，1.6.0，可读取，D01-D16都没有任何问题”。DEVICE_RESULTS.json 逐项记录为 **16 通过/0 失败/0 待执行（均为用户回报）**，设备一加 Ace 2、系统规范记为 ColorOS 16、原应用 1.6.0、学习备份可读取。原文/来源见 DEVICE_REPORT-20261104.md；GPT 没有通过 ADB 独立执行。底层 Android 版本、输入法、分项实测数值和手机侧 APK 哈希未单独提供，字段保留未知，不把 ColorOS 16 推断成 Android 16。候选本地哈希再次核对未变，材料已提交最终裁定。

## 升级、回退与发布边界

Room 5 升级新增考试终止台账，学习备份仍3，不含运行考试/本机台账/API Key，速记独立备份；诊断 ZIP含原库及考试缓存，不能作为学习备份直接导入。旧 v4 APK不能直接读取 v5，源码回退不降级数据库。保留原库/原APK/升级前备份，优先保留 v5 的前向修复；必要旧版恢复需独立干净环境和用户确认数据替换，不能直接卸载清数据绕过。

当前没有业务新补丁，没有修改 Architect 共享派发或 FRONTEND_HANDOFF，没有读取/打印私钥，没有覆盖 1.6.0 APK、推送、创建稳定标签/Release。候选可供试装；设备与产物验收闭环后由 Architect 按用户既有授权作最终发布裁定。状态：**候选核验与用户设备回报已记录，待 Architect 最终发布裁定**。