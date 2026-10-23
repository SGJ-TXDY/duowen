package cn.embeddedquiz

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun Settings(vm: QuizViewModel) {
    var key by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::importUri)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::restoreUri)
        }
    val backup =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(vm::backupUri)
        }
    if (confirm.isNotBlank())
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text(confirm) },
            text = {
                Text(
                    if (confirm == "重置复习进度") "清除所有答题历史与复习安排，保留收藏、代码、笔记、便签和 AI 缓存。"
                    else "选择备份后将替换全部题库、进度、收藏、笔记、便签和 AI 缓存。旧版备份不含便签时保留当前便签。API Key 保持不变。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirm == "重置复习进度") vm.resetProgress()
                        else
                            restore.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream",
                                )
                            )
                        confirm = ""
                    }
                ) {
                    Text("继续")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("取消") } },
        )
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 30.dp),
    ) {
        item { Heading("DeepSeek 设置") }
        item { Text("官方地址 https://api.deepseek.com\n${if(vm.hasKey)"已保存加密凭据" else "未设置 API Key"}") }
        item {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("输入新的 API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("保存 Key", key.isNotBlank() && !vm.busy) {
                    vm.saveKey(key)
                    key = ""
                }
                Action("删除 Key", !vm.busy) {
                    vm.saveKey("")
                    key = ""
                }
            }
        }
        item {
            Picker("模型", vm.model, listOf("deepseek-flash", "deepseek-v4-pro"), vm::chooseModel)
        }
        item {
            Text(
                "累计输入 ${vm.aiEntries.sumOf {it.inputTokens}} / 输出 ${vm.aiEntries.sumOf {it.outputTokens}} tokens\n估算费用 $${"%.6f".format(vm.aiEntries.sumOf {it.estimatedUsd})} USD。按 2026-10-21 高峰费率估算，实际以官方账单为准；读取本地缓存不收费。"
            )
        }
        item {
            HorizontalDivider()
            Heading("题库与数据")
        }
        item {
            Text(
                "${vm.questions.size} 题 · ${vm.questions.count {it.reviewRequired}} 题待核对\nJSON 导入采用相同 ID 跳过策略，不覆盖已有答案。备份包含全部题目、图片、进度、历史、笔记、便签和 AI 解析，不含 Key。"
            )
        }
        item {
            Action("导入 JSON 题库", !vm.busy) {
                import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        item {
            Action("导出完整备份", !vm.busy) { backup.launch("EmbeddedQuiz-${LocalDate.now()}.json") }
        }
        item { Action("恢复备份", !vm.busy) { confirm = "恢复备份" } }
        item { Action("重置复习进度", !vm.busy) { confirm = "重置复习进度" } }
    }
}
