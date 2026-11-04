package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Perceived difficulty for one question; a tap saves right away. The selection always mirrors the
 * stored value, so nothing stale is written back and switching questions cannot leak a choice.
 */
@Composable
fun PerceivedDifficultyChips(vm: QuizViewModel, q: Question) {
    val stored = vm.learning.metadata[q.id]?.perceivedDifficulty.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("我感觉的难度", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "未设置", "易" to "易", "中" to "中", "难" to "难").forEach { (value, label) ->
                val chosen = value == stored
                FilterChip(
                    selected = chosen,
                    onClick = { if (!chosen) vm.savePerceivedDifficulty(q.id, value) },
                    enabled = !vm.busy,
                    label = { Text(label) },
                    leadingIcon =
                        if (chosen) {
                            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                    shape = MaterialTheme.shapes.small,
                    colors = appChipColors(),
                    border = appChipBorder(enabled = !vm.busy, selected = chosen),
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        Text(
            "原题难度：${q.difficulty}。点选即保存，个人感受不代表官方难度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun VerifiedSolutionEditor(vm: QuizViewModel, q: Question, showHeading: Boolean = true) {
    val meta = vm.learning.metadata[q.id] ?: QuestionPersonal(q.id)
    var code by rememberSaveable(q.id, meta.verifiedCode) { mutableStateOf(meta.verifiedCode) }
    var verified by rememberSaveable(q.id, meta.codeVerified) { mutableStateOf(meta.codeVerified) }
    var editing by rememberSaveable(q.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) Heading("我的参考解法")
        Text("只保存你手动确认的解法，AI 不会自动写入。")
        if (meta.verifiedCode.isNotBlank()) {
            Text(if (meta.codeVerified) "已核对 · 个人参考解法" else "个人参考解法 · 尚未核对")
            CodeBlock(meta.verifiedCode, "个人参考解法")
        }
        OutlinedButton(
            onClick = { editing = !editing },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (editing) "收起编辑" else "编辑个人参考解法")
        }
        if (editing) {
            CodeEditor(code) {
                code = it
                verified = false
            }
            Row {
                Checkbox(
                    checked = verified,
                    onCheckedChange = { verified = it },
                    enabled = code.isNotBlank(),
                )
                Text("我已自行核对此解法", Modifier.padding(top = 14.dp))
            }
            Action("保存个人参考解法", !vm.busy) {
                vm.saveVerifiedSolution(q.id, code, verified)
            }
        }
    }
}
