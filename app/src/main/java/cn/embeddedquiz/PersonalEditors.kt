package cn.embeddedquiz

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PersonalMarksEditor(vm: QuizViewModel, q: Question) {
    val meta = vm.learning.metadata[q.id] ?: QuestionPersonal(q.id)
    var tags by rememberSaveable(q.id, meta.tags) { mutableStateOf(meta.tags) }
    var difficulty by
        rememberSaveable(q.id, meta.perceivedDifficulty) {
            mutableStateOf(meta.perceivedDifficulty)
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("我的题目标记")
        Text("原题难度：${q.difficulty}。个人感受单独保存，不代表官方难度。")
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text("自定义标签（逗号分隔）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Picker("我感觉的难度", difficulty.ifBlank { "未设置" }, listOf("未设置", "易", "中", "难")) {
            difficulty = if (it == "未设置") "" else it
        }
        Action("保存个人标记", !vm.busy) {
            vm.saveMetadata(meta.copy(tags = tags, perceivedDifficulty = difficulty))
        }
    }
}

@Composable
fun VerifiedSolutionEditor(vm: QuizViewModel, q: Question) {
    val meta = vm.learning.metadata[q.id] ?: QuestionPersonal(q.id)
    var code by rememberSaveable(q.id, meta.verifiedCode) { mutableStateOf(meta.verifiedCode) }
    var verified by rememberSaveable(q.id, meta.codeVerified) { mutableStateOf(meta.codeVerified) }
    var editing by rememberSaveable(q.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("我的参考解法")
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
                vm.saveMetadata(meta.copy(verifiedCode = code, codeVerified = verified))
            }
        }
    }
}
