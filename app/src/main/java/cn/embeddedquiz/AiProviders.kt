package cn.embeddedquiz

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object AiModels {
    const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
    private val modelId = Regex("[A-Za-z0-9_.:/-]{1,128}")
    fun valid(model: String) = modelId.matches(model)
    fun endpoint(raw: String): String {
        val value = raw.trim()
        require(value.length in 1..2048 && value.none { it.isISOControl() }) { "API URL 无效" }
        val url = value.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedQuery == null && url.fragment == null) { "请输入不含凭据、查询参数或片段的 HTTPS 地址" }
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath(if (path.endsWith("/chat/completions")) path else "$path/chat/completions")
            .build().toString().also { require(it.length <= 2048) { "API URL 过长" } }
    }

    fun validBillingModel(model: String): Boolean {
        if (model in DeepSeekClient.MODELS) return true
        val parts = model.split(':', limit = 4)
        return parts.size == 4 && parts[0] == "custom" && valid(parts[3]) && parts[3] !in DeepSeekClient.MODELS &&
            runCatching { AiJson.uuid(parts[1]); AiJson.uuid(parts[2]) }.isSuccess
    }

    fun billingLabel(model: String): String {
        if (model in DeepSeekClient.MODELS || !validBillingModel(model)) return model
        val (_, id, revision, raw) = model.split(':', limit = 4)
        return "$raw · 自定义 ${id.take(6)}/${revision.take(6)}"
    }
}

/** The actual URL and Key never enter a frozen request or learning backup. */
internal data class AiFrozenRoute(val profileId: String, val revision: String, val endpointHash: String) {
    fun json() = JSONObject().put("profileId", profileId).put("revision", revision).put("endpointHash", endpointHash)
    fun billingModel(model: String) = "custom:$profileId:$revision:$model"

    companion object {
        fun read(snapshot: JSONObject): AiFrozenRoute? {
            if (!snapshot.has("route")) return null
            val route = snapshot.getJSONObject("route")
            require(route.keys().asSequence().toSet() == setOf("profileId", "revision", "endpointHash"))
            return AiFrozenRoute(AiJson.string(route, "profileId").also(AiJson::uuid),
                AiJson.string(route, "revision").also(AiJson::uuid), AiJson.string(route, "endpointHash").also(AiJson::hash))
        }
    }
}

internal data class AiProviderProfile(
    val id: String, val name: String, val url: String, val model: String, val revision: String,
    internal val slot: String, val supportsTools: Boolean,
) {
    val label get() = "$name · $model"
    val route get() = AiFrozenRoute(id, revision, DeepSeekClient.hash(url))
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("model", model)
        .put("revision", revision).put("slot", slot).put("supportsTools", supportsTools)
}

internal class AiResolvedTarget(val endpoint: String, val key: String) {
    override fun toString() = "AiResolvedTarget([redacted])"
}
internal class AiProviderUnavailable : IllegalStateException("PROVIDER_CHANGED")
internal interface AiProviderKeys {
    fun read(id: String): String
    fun save(id: String, value: String)
    fun exists(id: String): Boolean
}

internal class AiProviderStore(private val prefs: SharedPreferences, private val keys: AiProviderKeys) {
    constructor(prefs: SharedPreferences, vault: KeyVault) : this(prefs, object : AiProviderKeys {
        override fun read(id: String) = vault.readSlot(id)
        override fun save(id: String, value: String) = vault.saveSlot(id, value)
        override fun exists(id: String) = vault.existsSlot(id)
    })

    companion object {
        const val MAX_PROFILES = 10
        const val PREF = "ai.customProviders.v2"
        private val lock = Any()
        fun forContext(context: Context) =
            AiProviderStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE), KeyVault(context))
    }

    fun profiles(): List<AiProviderProfile> = synchronized(lock) {
        val rows = JSONArray(prefs.getString(PREF, "[]"))
        require(rows.length() <= MAX_PROFILES)
        AiJson.objects(JSONObject().put("profiles", rows), "profiles").map { o ->
            require(o.keys().asSequence().toSet() == setOf("id", "name", "url", "model", "revision", "slot", "supportsTools"))
            AiProviderProfile(o.getString("id"), o.getString("name"), o.getString("url"), o.getString("model"),
                o.getString("revision"), o.getString("slot"), AiJson.boolean(o, "supportsTools")).also {
                listOf(it.id, it.revision, it.slot).forEach(AiJson::uuid)
                require(it.name.isNotBlank() && it.name.length <= 60 && it.name.none(Char::isISOControl))
                require(AiModels.valid(it.model) && it.model !in DeepSeekClient.MODELS && AiModels.endpoint(it.url) == it.url)
            }
        }.also { list ->
            require(list.map { it.id }.distinct().size == list.size && list.map { it.model }.distinct().size == list.size)
        }
    }

    fun selectedModel(): String = synchronized(lock) {
        prefs.getString("model", DeepSeekClient.FLASH)
            .takeIf { it in DeepSeekClient.MODELS || profiles().any { p -> p.model == it } } ?: DeepSeekClient.FLASH
    }

    fun select(model: String) = synchronized(lock) {
        require(model in DeepSeekClient.MODELS || profiles().any { it.model == model }) { "接口不存在，请重新选择" }
        prefs.writeDurably { putString("model", model) }
    }

    fun hasKey(id: String): Boolean = synchronized(lock) {
        profiles().singleOrNull { it.id == id }?.let { keys.exists(it.slot) } == true
    }

    fun bind(model: String): AiProviderProfile = synchronized(lock) {
        profiles().singleOrNull { it.model == model && keys.exists(it.slot) } ?: throw AiProviderUnavailable()
    }

    fun resolve(model: String, route: AiFrozenRoute): AiResolvedTarget = synchronized(lock) {
        val profile = profiles().singleOrNull { it.id == route.profileId && it.model == model && it.route == route }
            ?: throw AiProviderUnavailable()
        val key = keys.read(profile.slot)
        if (key.isBlank()) throw AiProviderUnavailable()
        AiResolvedTarget(profile.url, key)
    }

    fun save(id: String?, name: String, url: String, model: String, key: String, supportsTools: Boolean = false): AiProviderProfile =
        synchronized(lock) {
            val list = profiles()
            val old = id?.let { value -> list.singleOrNull { it.id == value } ?: throw IllegalArgumentException("接口不存在") }
            require(old != null || list.size < MAX_PROFILES) { "最多保存 10 个自定义接口" }
            val title = name.trim()
            val modelId = model.trim()
            require(title.length in 1..60 && title.none(Char::isISOControl)) { "名称需为 1–60 个可见字符" }
            require(AiModels.valid(modelId) && modelId !in DeepSeekClient.MODELS) { "自定义模型 ID 无效或与内置模型重复" }
            require(list.none { it.id != id && it.model == modelId }) { "模型 ID 已存在" }
            val endpoint = AiModels.endpoint(url)
            val secret = key.trim()
            val nextUrl = endpoint.toHttpUrl()
            val previousUrl = old?.url?.toHttpUrl()
            val sameOrigin = previousUrl != null && previousUrl.host == nextUrl.host && previousUrl.port == nextUrl.port
            if (secret.isEmpty()) {
                require(old != null && sameOrigin && keys.exists(old.slot)) { "新接口或跨域修改需重新输入 API Key" }
            } else require(secret.length <= 4096 && secret.all { it in '!'..'~' }) { "API Key 格式无效" }
            val slot = if (secret.isNotEmpty()) UUID.randomUUID().toString() else requireNotNull(old).slot
            val revision = if (old?.url == endpoint && old.model == modelId && old.supportsTools == supportsTools)
                old.revision else UUID.randomUUID().toString()
            val profile = AiProviderProfile(old?.id ?: UUID.randomUUID().toString(), title, endpoint, modelId, revision, slot, supportsTools)
            val selected = old != null && selectedModel() == old.model
            if (secret.isNotEmpty()) keys.save(slot, secret)
            try {
                val next = list.filterNot { it.id == id } + profile
                prefs.writeDurably {
                    putString(PREF, JSONArray(next.map { it.json() }).toString())
                    if (selected) putString("model", profile.model)
                }
            } catch (e: Exception) {
                if (secret.isNotEmpty()) runCatching { keys.save(slot, "") }
                throw e
            }
            if (old != null && old.slot != slot) runCatching { keys.save(old.slot, "") }
            profile
        }

    fun delete(id: String) = synchronized(lock) {
        val list = profiles()
        val old = list.singleOrNull { it.id == id } ?: return@synchronized
        val selected = selectedModel() == old.model
        keys.save(old.slot, "")
        prefs.writeDurably {
            putString(PREF, JSONArray(list.filterNot { it.id == id }.map { it.json() }).toString())
            if (selected) putString("model", DeepSeekClient.FLASH)
        }
    }
}
