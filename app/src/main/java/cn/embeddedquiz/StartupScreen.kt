package cn.embeddedquiz

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private val StartupStage.label
    get() =
        when (this) {
            StartupStage.DATABASE -> "打开本机数据库"
            StartupStage.VALIDATION -> "检查历史数据"
            StartupStage.BANK -> "准备离线题库"
            StartupStage.INDEX -> "检查分类索引"
            StartupStage.EXAM -> "恢复未完成考试"
        }

/** Before Ready nothing else of the app is composed, so no business effect can run early. */
@Composable
internal fun StartupLoading(vm: QuizViewModel, state: StartupState.Loading) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("嵌入式面试助手", style = MaterialTheme.typography.headlineSmall)
        Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Determinate: an indeterminate indicator never lets Compose tests reach idle.
        LinearProgressIndicator(progress = { vm.importProgress }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A failed start: what failed, and only the actions the backend offers for it. Results of this
 * attempt's actions stay on screen; a new attempt starts with a clean page.
 */
@Composable
internal fun StartupErrorScreen(vm: QuizViewModel, state: StartupState.Error) {
    key(state.attempt) { StartupErrorContent(vm, state) }
}

private data class RecoveryNote(val text: String, val failed: Boolean)

@Composable
private fun StartupErrorContent(vm: QuizViewModel, state: StartupState.Error) {
    val scope = rememberCoroutineScope()
    // Set on the tap itself, so a second tap cannot start a second action before busy is published.
    var working by remember { mutableStateOf(false) }
    val busy = working || vm.startupRecoveryBusy
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticsFailed by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<RecoveryNote?>(null) }
    var restoreNote by remember { mutableStateOf<RecoveryNote?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun run(action: suspend () -> StartupRecoveryResult, done: (StartupRecoveryResult) -> Unit) {
        if (busy) return
        working = true
        scope.launch {
            val result =
                try {
                    action()
                } finally {
                    working = false
                }
            done(result)
        }
    }

    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            // Cancelling the picker calls nothing.
            if (uri != null)
                run({ vm.exportStartupDiagnostics(uri) }) { result ->
                    exportNote =
                        when (result) {
                            StartupRecoveryResult.Completed -> RecoveryNote("诊断包已导出，请自行妥善保管。", false)
                            is StartupRecoveryResult.Rejected -> RecoveryNote(result.message, true)
                            is StartupRecoveryResult.Failed ->
                                RecoveryNote("${result.message}。目标文件可能不完整，可以重新导出。", true)
                        }
                }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) pendingRestore = uri
        }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("用备份替换本机数据？") },
            text = {
                Text(
                    "将用所选学习备份替换题库、学习历史和复习安排等备份包含的数据，本机现有的这些数据会被覆盖。" +
                        "有进行中、待清理或状态无法确认的考试时会被拒绝，不会清除。诊断包不能用于恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        run({ vm.restoreStartupBackup(uri, confirmReplace = true) }) { result ->
                            restoreNote =
                                when (result) {
                                    // The page follows the new attempt; this is not reported as a start.
                                    StartupRecoveryResult.Completed -> RecoveryNote("备份已写入，正在重新启动…", false)
                                    is StartupRecoveryResult.Rejected -> RecoveryNote(result.message, true)
                                    is StartupRecoveryResult.Failed -> RecoveryNote(result.message, true)
                                }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("确认替换")
                }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "应用未能启动",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp).semantics {
                        liveRegion = LiveRegionMode.Assertive
                    },
            )
        }
        Text(
            "错误代码：${state.code} · 阶段：${state.stage.label} · 第 ${state.attempt} 次启动",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy)
            Text(
                "正在处理，请稍候…",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        Action("重试启动", !busy) { vm.retryInitialization() }

        StartupSection("诊断信息") {
            Text(
                "只包含启动状态、阶段、错误代码和文件大小等信息，不含题目内容、作答或密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryAction(if (diagnosticsOpen) "收起诊断" else "查看诊断", modifier = Modifier.fillMaxWidth()) {
                if (diagnosticsOpen) diagnosticsOpen = false
                else
                    scope.launch {
                        try {
                            diagnostics = vm.readStartupDiagnostics()
                            diagnosticsFailed = false
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            diagnosticsFailed = true
                        }
                        diagnosticsOpen = true
                    }
            }
            if (diagnosticsOpen) {
                if (diagnosticsFailed) ErrorNotice("诊断信息读取失败，可以再试一次，或直接导出诊断包。")
                else
                    diagnostics?.let { text ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    // Indentation only; the content is exactly what the backend returned.
                                    remember(text) { runCatching { JSONObject(text).toString(2) }.getOrDefault(text) },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
            }
        }

        // Distinct from the button label, so a screen reader does not announce the same words twice.
        StartupSection("诊断包") {
            Text(
                "诊断包包含题库、学习记录与考试缓存，请自行保管；这是诊断包，不能作为学习备份直接导入。" +
                    "文件只保存到你选择的位置，应用不会上传或发送。",
                style = MaterialTheme.typography.bodyMedium,
            )
            SecondaryAction("导出诊断包", !busy, Modifier.fillMaxWidth()) {
                export.launch("嵌入式面试助手-诊断-${LocalDate.now()}.zip")
            }
            exportNote?.let { RecoveryNoteText(it) }
        }

        StartupSection("从学习备份恢复") {
            if (state.canRestoreBackup) {
                Text(
                    "选择之前导出的学习备份（JSON，备份格式 1～3）。选择后会先请你确认，确认前不会修改任何数据。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryAction("选择学习备份", !busy, Modifier.fillMaxWidth()) {
                    restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            } else
                Text(
                    "数据库尚不可安全使用，不能恢复备份。请先导出诊断包保留原库，再重试启动。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            restoreNote?.let { RecoveryNoteText(it) }
        }
    }
}

@Composable
private fun StartupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun RecoveryNoteText(note: RecoveryNote) {
    val modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (note.failed) Box(modifier) { ErrorNotice(note.text) }
    else Text(note.text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}
