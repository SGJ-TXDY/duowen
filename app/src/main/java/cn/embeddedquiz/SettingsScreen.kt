package cn.embeddedquiz

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun Settings(vm: QuizViewModel) {
    val settings = vm.learning.settings
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var goal by remember(settings.dailyGoal) { mutableStateOf(settings.dailyGoal.toString()) }
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
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            vm.updateSettings {
                it.copy(reminderEnabled = allowed, notificationPermissionAsked = true)
            }
            if (!allowed) vm.notice = "系统未允许通知。可在系统通知设置中手动开启，应用不会反复弹窗。"
        }
    if (confirm.isNotBlank())
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text(confirm) },
            text = {
                Text(
                    if (confirm == "重置复习进度") "清除答题历史和复习安排，保留收藏、代码、笔记、便签和个人核对资料。"
                    else "选择备份后替换其中包含的数据。旧版备份没有的新字段会保留当前数据；API Key 不变。"
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
        modifier = Modifier.testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Heading("阅读与显示") }
        item {
            Picker(
                "深色模式",
                ThemeChoice.valueOf(settings.theme).label,
                ThemeChoice.entries.map { it.label },
            ) { label ->
                vm.updateSettings {
                    it.copy(
                        theme = ThemeChoice.entries.first { choice -> choice.label == label }.name
                    )
                }
            }
        }
        item {
            Picker(
                "字号",
                FontChoice.valueOf(settings.fontSize).label,
                FontChoice.entries.map { it.label },
            ) { label ->
                vm.updateSettings {
                    it.copy(
                        fontSize = FontChoice.entries.first { choice -> choice.label == label }.name
                    )
                }
            }
        }
        item { Heading("每日学习") }
        item {
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it.filter(Char::isDigit).take(4) },
                label = { Text("每日目标（1–1000 题）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Action("保存每日目标", !vm.busy && (goal.toIntOrNull() ?: 0) in 1..1000) {
                vm.updateSettings { it.copy(dailyGoal = goal.toInt()) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("每日本地复习提醒", Modifier.weight(1f).padding(top = 14.dp))
                Switch(
                    checked = settings.reminderEnabled,
                    enabled = !vm.busy,
                    onCheckedChange = { enabled ->
                        if (!enabled) vm.updateSettings { it.copy(reminderEnabled = false) }
                        else if (ReviewReminders.permitted(context))
                            vm.updateSettings { it.copy(reminderEnabled = true) }
                        else if (
                            Build.VERSION.SDK_INT >= 33 && !settings.notificationPermissionAsked
                        )
                            vm.markNotificationRequested {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        else vm.notice = "通知尚未获准，请在系统通知设置中手动开启。应用不会再次弹窗。"
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    TimePickerDialog(
                            context,
                            { _, h, m ->
                                vm.updateSettings { it.copy(reminderMinute = h * 60 + m) }
                            },
                            settings.reminderMinute / 60,
                            settings.reminderMinute % 60,
                            true,
                        )
                        .show()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    "提醒时间：${(settings.reminderMinute/60).toString().padStart(2,'0')}:${(settings.reminderMinute%60).toString().padStart(2,'0')}"
                )
            }
        }
        item { Text("仅在有到期题时提醒，不联网。系统后台调度可能延迟；为遵守权限限制，不申请开机广播或精确闹钟权限，重启手机后请打开一次应用以恢复安排。") }
        item {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("打开系统通知设置")
            }
        }
        item {
            HorizontalDivider()
            Heading("DeepSeek 设置")
        }
        item { Text("${if(vm.hasKey)"已保存加密凭据" else "未设置 API Key"}。只有主动点击 AI 按钮才联网。") }
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
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
        item { Picker("模型", vm.model, DeepSeekClient.MODELS, vm::chooseModel) }
        item {
            OutlinedButton(
                onClick = { vm.page = Page.COSTS },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("查看按日、按模型费用估算")
            }
        }
        item {
            HorizontalDivider()
            Heading("题库与数据")
        }
        item {
            Text(
                "${vm.questions.size} 题 · 原始题库 JSON 保持独立。备份包含图片、进度、历史、便签、个人核对答案、标签、参考解法与学习设置，不包含 API Key 和错误日志。"
            )
        }
        item {
            Action("导入 JSON 题库", !vm.busy) {
                import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        item { Action("导出完整备份", !vm.busy) { backup.launch("学习备份-${LocalDate.now()}.json") } }
        item { Action("恢复备份", !vm.busy) { confirm = "恢复备份" } }
        item { Action("重置复习进度", !vm.busy) { confirm = "重置复习进度" } }
        item {
            OutlinedButton(
                onClick = { vm.page = Page.ERROR_LOG },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("查看本地错误记录")
            }
        }
    }
}

@Composable
fun ErrorLogScreen(vm: QuizViewModel) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.loadErrorLog() }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading("本地错误记录")
        Text("最多保存 512KB，仅含时间、操作类别和异常类型，不含 Key 或题目全文，不会上传。")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Action("复制错误记录") { clipboard.setText(AnnotatedString(vm.errorLogText)) }
            Action("清除错误记录", !vm.busy) { vm.clearErrorLog() }
        }
        SelectionContainer { Text(vm.errorLogText.ifBlank { "暂无错误记录" }) }
    }
}
