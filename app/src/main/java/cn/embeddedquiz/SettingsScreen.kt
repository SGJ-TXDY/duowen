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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/*
 * 设置 (PLAN-1.7.5): a home page of entries with a short status each, and one page per section —
 * 外观, AI 模型, 复习设置, 题库与备份 and 诊断 — built from the rounded groups shared with 更多. Every
 * control keeps the behavior it had on the single 1.7.4 page.
 */

private fun clock(minute: Int) = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

/** The grouped page body: inset, scrollable, with room after the last group. */
@Composable
private fun SettingsPage(tag: String, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(tag),
        contentPadding = PaddingValues(start = PAGE_INSET, end = PAGE_INSET, top = 4.dp, bottom = 32.dp),
        content = content,
    )
}

@Composable
fun Settings(vm: QuizViewModel) {
    val settings = vm.learning.settings
    val more = MaterialTheme.more
    SettingsPage("settings-list") {
        item {
            RoundedGroup {
                GroupRow(
                    "外观",
                    AppIcons.Sun,
                    tint = more.tag,
                    value = "${ThemeChoice.valueOf(settings.theme).label} · ${FontChoice.valueOf(settings.fontSize).label}号字 · ${accentOf(vm.accent.id).label}",
                ) { vm.navigate(Page.SETTINGS_APPEARANCE) }
                GroupDivider()
                GroupRow(
                    "AI 模型",
                    AppIcons.Bot,
                    tint = more.ai,
                    subtitle = if (vm.hasKey) "已保存加密 Key" else "未设置 API Key",
                    value = "DeepSeek · ${vm.model}",
                ) { vm.navigate(Page.SETTINGS_AI) }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            RoundedGroup {
                GroupRow(
                    "复习设置",
                    AppIcons.Bell,
                    tint = more.review,
                    value = "每日 ${settings.dailyGoal} 题 · " + if (settings.reminderEnabled) "提醒 ${clock(settings.reminderMinute)}" else "提醒关闭",
                ) { vm.navigate(Page.SETTINGS_REVIEW) }
            }
        }
        item { GroupCaption("数据") }
        item {
            RoundedGroup {
                GroupRow("题库与备份", AppIcons.Database, tint = more.stats, value = "${vm.questions.size} 题") { vm.navigate(Page.SETTINGS_DATA) }
                GroupDivider()
                GroupRow("诊断", AppIcons.Stethoscope, tint = more.settings, value = "本地错误记录") { vm.navigate(Page.ERROR_LOG) }
            }
        }
        item { GroupFootnote("速记的导入、备份和恢复在速记页右上角菜单，与学习备份分开。") }
    }
}

@Composable
fun AppearanceSettings(vm: QuizViewModel) {
    val settings = vm.learning.settings
    SettingsPage("settings-appearance") {
        item { GroupCaption("深色模式") }
        item {
            RoundedGroup(Modifier.testTag("theme-choices")) {
                GroupRows(ThemeChoice.entries, dividerStart = 16.dp) { choice ->
                    GroupChoiceRow(choice.label, settings.theme == choice.name) { vm.updateSettings { it.copy(theme = choice.name) } }
                }
            }
        }
        item { GroupCaption("字号") }
        item {
            RoundedGroup(Modifier.testTag("font-choices")) {
                GroupRows(FontChoice.entries, dividerStart = 16.dp) { choice ->
                    GroupChoiceRow(choice.label, settings.fontSize == choice.name) { vm.updateSettings { it.copy(fontSize = choice.name) } }
                }
            }
        }
        item { GroupCaption("主题色") }
        item {
            RoundedGroup {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    AccentPicker(vm.accent.id) { id ->
                        runCatching { vm.accent.choose(id) }.onFailure { vm.notice = "主题色保存失败，请重试" }
                    }
                }
            }
        }
    }
}

@Composable
fun AiModelSettings(vm: QuizViewModel) {
    var key by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf(false) }
    val more = MaterialTheme.more
    SettingsPage("settings-ai") {
        item {
            RoundedGroup(Modifier.padding(top = 4.dp).testTag("ai-current")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupBadge(AppIcons.Bot, more.ai, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("当前使用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("DeepSeek · ${vm.model}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (vm.hasKey) "已保存加密 Key · 只有主动点击 AI 按钮才联网" else "未设置 API Key · 设置后才能使用 AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { GroupCaption("内置 DeepSeek") }
        item {
            RoundedGroup(Modifier.testTag("model-choices")) {
                GroupRows(DeepSeekClient.MODELS, dividerStart = 16.dp) { model ->
                    GroupChoiceRow(model, vm.model == model) { vm.chooseModel(model) }
                }
                GroupDivider(16.dp)
                GroupRow(
                    "API Key",
                    AppIcons.KeyRound,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    value = if (vm.hasKey) "已加密保存" else "未设置",
                    chevron = !editingKey,
                ) { editingKey = !editingKey }
                if (editingKey)
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("输入新的 API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("api-key-field"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.saveKey(key)
                                    key = ""
                                    editingKey = false
                                },
                                enabled = key.isNotBlank() && !vm.busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("保存 Key") }
                            OutlinedButton(
                                onClick = {
                                    vm.saveKey("")
                                    key = ""
                                    editingKey = false
                                },
                                enabled = vm.hasKey && !vm.busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("删除 Key") }
                        }
                    }
            }
        }
        item { GroupCaption("自定义接口") }
        item {
            RoundedGroup {
                GroupRow(
                    "自定义接口",
                    AppIcons.Plus,
                    subtitle = "可填名称、地址、模型 ID 和加密 Key；接口安全审核通过后开放",
                    enabled = false,
                    chevron = false,
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            RoundedGroup {
                GroupRow("AI 费用估算", AppIcons.Coin, tint = more.cost, subtitle = "按日、按模型查看") { vm.navigate(Page.COSTS) }
            }
        }
        item { GroupFootnote("Key 只在本机加密保存，不进入学习备份。") }
    }
}

@Composable
fun ReviewSettings(vm: QuizViewModel) {
    val settings = vm.learning.settings
    val context = LocalContext.current
    var goal by remember(settings.dailyGoal) { mutableStateOf(settings.dailyGoal.toString()) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            vm.updateSettings { it.copy(reminderEnabled = allowed, notificationPermissionAsked = true) }
            if (!allowed) vm.notice = "系统未允许通知。可在系统通知设置中手动开启，应用不会反复弹窗。"
        }
    val goalValid = (goal.toIntOrNull() ?: 0) in 1..1000
    SettingsPage("settings-review") {
        item { GroupCaption("每日学习") }
        item {
            RoundedGroup {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Target, contentDescription = null, Modifier.size(24.dp), tint = MaterialTheme.more.review)
                    Spacer(Modifier.width(16.dp))
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it.filter(Char::isDigit).take(4) },
                        label = { Text("每日目标（1–1000 题）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = goal.isNotEmpty() && !goalValid,
                        modifier = Modifier.weight(1f).testTag("daily-goal"),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { vm.updateSettings { it.copy(dailyGoal = goal.toInt()) } },
                        enabled = !vm.busy && goalValid && goal.toInt() != settings.dailyGoal,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("保存每日目标") }
                }
            }
        }
        item { GroupCaption("复习提醒") }
        item {
            RoundedGroup {
                GroupSwitchRow("每日本地复习提醒", settings.reminderEnabled, icon = AppIcons.Bell, enabled = !vm.busy) { enabled ->
                    if (!enabled) vm.updateSettings { it.copy(reminderEnabled = false) }
                    else if (ReviewReminders.permitted(context)) vm.updateSettings { it.copy(reminderEnabled = true) }
                    else if (Build.VERSION.SDK_INT >= 33 && !settings.notificationPermissionAsked)
                        vm.markNotificationRequested { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    else vm.notice = "通知尚未获准，请在系统通知设置中手动开启。应用不会再次弹窗。"
                }
                GroupDivider()
                GroupRow("提醒时间", AppIcons.Clock, tint = MaterialTheme.colorScheme.onSurfaceVariant, value = clock(settings.reminderMinute)) {
                    TimePickerDialog(
                            context,
                            { _, h, m -> vm.updateSettings { it.copy(reminderMinute = h * 60 + m) } },
                            settings.reminderMinute / 60,
                            settings.reminderMinute % 60,
                            true,
                        )
                        .show()
                }
                GroupDivider()
                GroupRow("系统通知设置", AppIcons.Settings, tint = MaterialTheme.colorScheme.onSurfaceVariant) {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
        }
        item {
            GroupFootnote(
                "仅在有到期题时提醒，不联网。系统后台调度可能延迟；为遵守权限限制，不申请开机广播或精确闹钟权限，重启手机后请打开一次应用以恢复安排。"
            )
        }
    }
}

@Composable
fun DataSettings(vm: QuizViewModel) {
    var confirm by remember { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importUri) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreUri) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::backupUri) }
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
                        else restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        confirm = ""
                    }
                ) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("取消") } },
        )
    SettingsPage("settings-data") {
        item { GroupCaption("题库") }
        item {
            RoundedGroup {
                GroupRow("导入 JSON 题库", AppIcons.Import, tint = MaterialTheme.more.stats, value = "${vm.questions.size} 题", enabled = !vm.busy) {
                    import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
            }
        }
        item { GroupFootnote("原始题库 JSON 保持独立。") }
        item { GroupCaption("学习备份") }
        item {
            RoundedGroup {
                GroupRow("导出完整备份", AppIcons.Upload, tint = MaterialTheme.more.review, enabled = !vm.busy) {
                    backup.launch("学习备份-${LocalDate.now()}.json")
                }
                GroupDivider()
                GroupRow("恢复备份", AppIcons.Sync, tint = MaterialTheme.more.verify, enabled = !vm.busy) { confirm = "恢复备份" }
            }
        }
        item {
            GroupFootnote("备份包含图片、进度、历史、便签、个人核对答案、标签、参考解法与学习设置，不包含 API Key、错误日志和速记。")
        }
        item { GroupCaption("复习进度") }
        item {
            RoundedGroup {
                GroupRow("重置复习进度", AppIcons.Review, danger = true, enabled = !vm.busy) { confirm = "重置复习进度" }
            }
        }
    }
}

/** Eight round swatches; each keeps a 48dp touch target around its 36dp color dot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: String, onChoose: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("主题色：${accentOf(selected).label}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        FlowRow(Modifier.testTag("accent-picker")) {
            ACCENTS.forEach { accent ->
                val chosen = accent.id == selected
                Box(
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .selectable(selected = chosen, role = Role.RadioButton) { onChoose(accent.id) }
                        .semantics { contentDescription = "主题色${accent.label}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .then(if (chosen) Modifier.border(2.dp, accent.color, CircleShape) else Modifier)
                            .padding(if (chosen) 4.dp else 2.dp)
                            .clip(CircleShape)
                            .background(accent.color),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) Icon(AppIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorLogScreen(vm: QuizViewModel) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.loadErrorLog() }
    SettingsPage("error-log") {
        item { GroupCaption("本地错误记录") }
        item {
            RoundedGroup {
                GroupRow("复制错误记录", AppIcons.ClipboardCheck, tint = MaterialTheme.colorScheme.onSurfaceVariant, chevron = false) {
                    clipboard.setText(AnnotatedString(vm.errorLogText))
                }
                GroupDivider()
                GroupRow("清除错误记录", AppIcons.Trash, danger = true, chevron = false, enabled = !vm.busy) { vm.clearErrorLog() }
            }
        }
        item { GroupFootnote("最多保存 512KB，仅含时间、操作类别和异常类型，不含 Key 或题目全文，不会上传。") }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            RoundedGroup {
                SelectionContainer {
                    Text(
                        vm.errorLogText.ifBlank { "暂无错误记录" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
