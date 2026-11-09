package cn.embeddedquiz

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * 设置 › AI 模型 (PLAN-1.7.5): the service in use, the built-in DeepSeek models and Key, and up to ten
 * custom OpenAI-compatible services that can be added, edited, switched to and deleted. Storage and
 * every rule (HTTPS only, a new Key when the host changes, one profile per model id) are GPT's
 * AiProviderStore; this page only shows them and passes the user's input through.
 */

/** What the AI 模型 page reads and changes. */
internal interface AiModelChoices {
    /** The model id requests use now: a DeepSeek model or a custom profile's model. */
    val selected: String
    val providers: List<AiProviderProfile>
    val deepSeekKey: Boolean
    fun hasKey(profileId: String): Boolean
    fun select(model: String)
    /** Throws IllegalArgumentException with the reason to show when the input is refused. */
    fun save(id: String?, name: String, url: String, model: String, key: String, supportsTools: Boolean): AiProviderProfile
    fun delete(id: String)
}

/**
 * The page's state over [AiProviderStore]. DeepSeek models also go through [chooseDeepSeek], so the
 * ViewModel's model follows; a custom model is selected in the store, where sending reads it.
 */
@Stable
internal class StoreModelSettings(
    private val store: AiProviderStore,
    private val deepSeekModel: () -> String,
    private val chooseDeepSeek: (String) -> Unit,
    private val keyState: () -> Boolean,
) : AiModelChoices {
    override var providers by mutableStateOf(runCatching { store.profiles() }.getOrDefault(emptyList()))
        private set
    private var custom by mutableStateOf(runCatching { store.selectedModel() }.getOrNull()?.takeIf { it !in DeepSeekClient.MODELS })
    private var keys by mutableStateOf(providers.associate { it.id to store.hasKey(it.id) })

    override val selected: String
        get() = custom?.takeIf { m -> providers.any { it.model == m } } ?: deepSeekModel()
    override val deepSeekKey: Boolean
        get() = keyState()

    override fun hasKey(profileId: String) = keys[profileId] == true

    private fun reload() {
        providers = store.profiles()
        keys = providers.associate { it.id to store.hasKey(it.id) }
        custom = store.selectedModel().takeIf { it !in DeepSeekClient.MODELS }
    }

    override fun select(model: String) {
        store.select(model)
        // The ViewModel's model follows. Custom models reach it once GPT's AiStateHolder wiring accepts
        // them (PLAN-1.7.5 接线补单 2); until then only built-in ones do, and the store keeps the choice.
        if (model in DeepSeekClient.MODELS) chooseDeepSeek(model) else runCatching { chooseDeepSeek(model) }
        reload()
    }

    override fun save(id: String?, name: String, url: String, model: String, key: String, supportsTools: Boolean): AiProviderProfile {
        val profile = store.save(id, name, url, model, key, supportsTools)
        reload()
        return profile
    }

    override fun delete(id: String) {
        store.delete(id)
        reload()
        // Deleting the selected service falls back to the built-in model.
        if (custom == null) chooseDeepSeek(store.selectedModel().takeIf { it in DeepSeekClient.MODELS } ?: DeepSeekClient.FLASH)
    }
}

/** The custom service being added (id null) or edited. */
private data class ProviderForm(
    val id: String?,
    val name: String = "",
    val url: String = "",
    val model: String = "",
    val key: String = "",
    val tools: Boolean = false,
    val error: String = "",
    val saving: Boolean = false,
)

private fun host(url: String) = url.toHttpUrlOrNull()?.host ?: url

@Composable
fun AiModelSettings(vm: QuizViewModel) {
    val context = LocalContext.current
    val settings =
        remember(vm) {
            StoreModelSettings(AiProviderStore.forContext(context.applicationContext), { vm.model }, vm::chooseModel, { vm.hasKey })
        }
    AiModelSettingsContent(settings, busy = vm.busy, onSaveDeepSeekKey = vm::saveKey) { vm.navigate(Page.COSTS) }
}

@Composable
internal fun AiModelSettingsContent(
    settings: AiModelChoices,
    busy: Boolean,
    /** Stores the built-in service's Key; "" deletes it. */
    onSaveDeepSeekKey: (String) -> Unit,
    onCosts: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var editingKey by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var deleting by remember { mutableStateOf<AiProviderProfile?>(null) }
    var notice by remember { mutableStateOf("") }
    val more = MaterialTheme.more
    val current = settings.providers.firstOrNull { it.model == settings.selected }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${p.name}」？") },
            text = {
                Text(
                    if (p.model == settings.selected) "它是当前使用的服务，删除后改用内置 DeepSeek。已保存的 Key 一并删除；已有讨论和费用记录保留。"
                    else "已保存的 Key 一并删除；已有讨论和费用记录保留。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        form = null
                        scope.launchSafely({ notice = it }) { settings.delete(p.id) }
                    },
                    modifier = Modifier.testTag("provider-delete-confirm"),
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-ai"),
        contentPadding = PaddingValues(start = PAGE_INSET, end = PAGE_INSET, top = 4.dp, bottom = 32.dp),
    ) {
        item {
            RoundedGroup(Modifier.padding(top = 4.dp).testTag("ai-current")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupBadge(AppIcons.Bot, more.ai, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("当前使用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            current?.let { "${it.name} · ${it.model}" } ?: "DeepSeek · ${settings.selected}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("ai-current-model"),
                        )
                        Text(
                            when {
                                current != null && settings.hasKey(current.id) -> "自定义接口 · ${host(current.url)} · 已保存加密 Key"
                                current != null -> "自定义接口 · ${host(current.url)} · 需要重新填写 Key"
                                settings.deepSeekKey -> "已保存加密 Key · 只有主动点击 AI 按钮才联网"
                                else -> "未设置 API Key · 设置后才能使用 AI"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (notice.isNotEmpty()) item { Box(Modifier.padding(top = 8.dp)) { ErrorNotice(notice) } }

        item { GroupCaption("内置 DeepSeek") }
        item {
            RoundedGroup(Modifier.testTag("model-choices")) {
                GroupRows(DeepSeekClient.MODELS, dividerStart = 16.dp) { model ->
                    GroupChoiceRow(model, settings.selected == model) {
                        scope.launchSafely({ notice = it }) { settings.select(model) }
                    }
                }
                GroupDivider(16.dp)
                GroupRow(
                    "API Key",
                    AppIcons.KeyRound,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    value = if (settings.deepSeekKey) "已加密保存" else "未设置",
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
                                    onSaveDeepSeekKey(key)
                                    key = ""
                                    editingKey = false
                                },
                                enabled = key.isNotBlank() && !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("保存 Key") }
                            OutlinedButton(
                                onClick = {
                                    onSaveDeepSeekKey("")
                                    key = ""
                                    editingKey = false
                                },
                                enabled = settings.deepSeekKey && !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("删除 Key") }
                        }
                    }
            }
        }

        item { GroupCaption("自定义接口") }
        item {
            RoundedGroup(Modifier.testTag("custom-providers")) {
                settings.providers.forEachIndexed { index, p ->
                    if (index > 0) GroupDivider(16.dp)
                    ProviderRow(p, selected = settings.selected == p.model, hasKey = settings.hasKey(p.id), onEdit = {
                        form = ProviderForm(p.id, p.name, p.url, p.model, "", p.supportsTools)
                    }) {
                        if (!settings.hasKey(p.id)) {
                            form = ProviderForm(p.id, p.name, p.url, p.model, "", p.supportsTools, error = "这个接口还没有可用的 Key，请先填写。")
                        } else scope.launchSafely({ notice = it }) { settings.select(p.model) }
                    }
                }
                if (settings.providers.isNotEmpty()) GroupDivider(16.dp)
                GroupRow(
                    "添加自定义接口",
                    AppIcons.Plus,
                    tint = MaterialTheme.colorScheme.primary,
                    subtitle = if (settings.providers.size >= AiProviderStore.MAX_PROFILES) "最多保存 ${AiProviderStore.MAX_PROFILES} 个" else "兼容 OpenAI 格式的 HTTPS 接口",
                    enabled = settings.providers.size < AiProviderStore.MAX_PROFILES && form == null,
                    chevron = false,
                    modifier = Modifier.testTag("provider-add"),
                ) { form = ProviderForm(null) }
            }
        }
        form?.let { f ->
            item(key = "form") {
                ProviderEditor(
                    f,
                    existing = settings.providers.firstOrNull { it.id == f.id },
                    onChange = { form = it },
                    onCancel = { form = null },
                    onDelete = { deleting = settings.providers.firstOrNull { it.id == f.id } },
                ) {
                    form = f.copy(saving = true, error = "")
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) { runCatching { settings.save(f.id, f.name, f.url, f.model, f.key, f.tools) } }
                        result
                            .onSuccess { saved ->
                                form = null
                                // A newly added service with a Key becomes the one in use.
                                if (f.id == null) runCatching { settings.select(saved.model) }.onFailure { notice = it.message ?: "切换失败" }
                            }
                            .onFailure { form = f.copy(saving = false, error = (it as? IllegalArgumentException)?.message ?: "保存失败，请重试") }
                    }
                }
            }
        }
        item {
            GroupFootnote(
                "Key 只加密保存在本机，不进入学习备份。地址只接受 HTTPS；换到其他域名需要重新填写 Key。自定义接口的费用显示为未知。开启「支持工具调用」后，速记讨论可按需查阅整篇速记，查到的摘录会发送给该服务。"
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            RoundedGroup {
                GroupRow("AI 费用估算", AppIcons.Coin, tint = more.cost, subtitle = "按日、按模型查看", onClick = onCosts)
            }
        }
    }
}

private fun CoroutineScope.launchSafely(onError: (String) -> Unit, action: () -> Unit) =
    launch {
        runCatching { action() }.onFailure { onError((it as? IllegalArgumentException)?.message ?: "操作失败，请重试") }
    }

@Composable
private fun ProviderRow(p: AiProviderProfile, selected: Boolean, hasKey: Boolean, onEdit: () -> Unit, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("provider-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(p.model, host(p.url), if (hasKey) "Key 已保存" else "需要 Key", if (p.supportsTools) "支持工具" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(AppIcons.Check, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Pencil, contentDescription = "编辑「${p.name}」", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProviderEditor(
    form: ProviderForm,
    existing: AiProviderProfile?,
    onChange: (ProviderForm) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    // Same host keeps the saved Key when the field is left empty; another host needs a new one.
    val sameHost = existing != null && form.url.trim().let { u -> (u.toHttpUrlOrNull() ?: "https://$u".toHttpUrlOrNull())?.host } == host(existing.url)
    RoundedGroup(Modifier.padding(top = 12.dp).testTag("provider-editor")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (existing == null) "添加自定义接口" else "编辑「${existing.name}」", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(form.name, { onChange(form.copy(name = it.take(60))) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider-name"))
            OutlinedTextField(
                form.url,
                { onChange(form.copy(url = it.trim())) },
                label = { Text("API 地址（HTTPS）") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("provider-url"),
            )
            OutlinedTextField(form.model, { onChange(form.copy(model = it.trim())) }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider-model"))
            OutlinedTextField(
                form.key,
                { onChange(form.copy(key = it)) },
                label = { Text("API Key") },
                placeholder = { Text(if (existing != null && sameHost) "留空则沿用已保存的 Key" else "必填") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("provider-key"),
            )
            if (existing != null && !sameHost && form.url.isNotBlank())
                Text("地址换到了其他域名，需要重新填写 Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("支持工具调用", style = MaterialTheme.typography.bodyLarge)
                    Text("速记讨论可按需查阅整篇速记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = form.tools, onCheckedChange = { onChange(form.copy(tools = it)) }, modifier = Modifier.testTag("provider-tools"))
            }
            if (form.error.isNotEmpty()) Box(Modifier.testTag("provider-error")) { ErrorNotice(form.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSave,
                    enabled = !form.saving && form.name.isNotBlank() && form.url.isNotBlank() && form.model.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("provider-save"),
                ) { Text(if (form.saving) "正在保存…" else "保存") }
                OutlinedButton(onClick = onCancel, enabled = !form.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                Spacer(Modifier.weight(1f))
                if (existing != null)
                    TextButton(onClick = onDelete, enabled = !form.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("provider-delete")) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
            }
        }
    }
}
