package com.verdroid.app.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A preset for the custom OpenAI-compatible provider (Settings → LLM provider). */
data class ProviderPreset(val id: String, val label: String, val baseUrl: String, val needsKey: Boolean, val note: String = "", val keyUrl: String? = null) {
    /** "Ollama (local)" → "Ollama"; used in model labels like "llama3 (Ollama)". */
    val shortName: String get() = label.substringBefore(" (")
    /** Local servers and Custom need a URL the user can see/edit; cloud presets hide it under Advanced. */
    val urlEditable: Boolean get() = !needsKey
}

object ProviderPresets {
    val ALL = listOf(
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1/", true, keyUrl = "https://platform.openai.com/api-keys"),
        ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1/", true, keyUrl = "https://console.groq.com/keys"),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1/", true, keyUrl = "https://platform.deepseek.com/api_keys"),
        ProviderPreset("together", "Together", "https://api.together.xyz/v1/", true, keyUrl = "https://api.together.ai/settings/api-keys"),
        ProviderPreset("ollama", "Ollama (local)", "http://localhost:11434/v1/", false, "Ollama server on this phone (e.g. in Termux) or another device"),
        ProviderPreset("lmstudio", "LM Studio (local)", "http://localhost:1234/v1/", false, "LM Studio's local server on a computer"),
        ProviderPreset("custom", "Custom", "", false, "Any service with an OpenAI-compatible API"),
    )
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.last()
    fun find(id: String?) = ALL.firstOrNull { it.id == id }
}

/** Pure URL / header rules (unit-tested). */
/** Self-test: a local server (Ollama, LM Studio, …) being off is normal → skip; real misconfiguration still fails. */
object ProviderProbe {
    enum class Verdict { SKIP, FAIL }

    /** localhost, loopback, private LAN ranges (10/8, 172.16/12, 192.168/16, 169.254/16) and *.local. */
    fun isLocal(baseUrl: String): Boolean {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\[(::1|fe80:[^\\]]*|f[cd][0-9a-f]{2}:[^\\]]*)\\]", RegexOption.IGNORE_CASE).containsMatchIn(baseUrl.trim())) return true
        val host = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\[?([^/\\]:?#]+)").find(baseUrl.trim())?.groupValues?.get(1)?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host == "::1" || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        val m = Regex("^172\\.(\\d+)\\.").find(host) ?: return false
        return m.groupValues[1].toInt() in 16..31
    }

    /** Connection refused / timeout / no route / unknown host (anywhere in the cause chain). */
    fun isUnreachable(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any {
        it is java.net.ConnectException || it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException ||
            it is java.net.UnknownHostException || it is java.io.InterruptedIOException && it.message.orEmpty().contains("timeout", true) ||
            it.message.orEmpty().let { m -> m.contains("Failed to connect", true) || m.contains("ECONNREFUSED") || m.contains("Connection refused", true) }
    }

    fun classify(baseUrl: String, e: Throwable): Verdict = if (isLocal(baseUrl) && isUnreachable(e)) Verdict.SKIP else Verdict.FAIL

    fun skipNote(name: String, baseUrl: String) = "$name server not reachable at $baseUrl (not running?)"
}

object ProviderUrls {
    /**
     * Normalizes a user-typed base URL to `scheme://host[:port]/path/` (trailing slash, no `/chat/completions` or
     * `/models` suffix). Missing scheme → https, except localhost/LAN → http. Returns null for junk.
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        if (!s.contains("://")) s = (if (isLocalOrLan(s.substringBefore('/').substringBefore(':'))) "http://" else "https://") + s
        val scheme = s.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        s = s.trimEnd('/')
        for (suffix in listOf("/chat/completions", "/completions", "/models")) if (s.endsWith(suffix)) s = s.removeSuffix(suffix)
        val host = host(s) ?: return null
        if (host.isEmpty()) return null
        return "$s/"
    }

    fun host(url: String): String? = runCatching {
        url.substringAfter("://").substringBefore('/').substringBefore('?').let { hp ->
            if (hp.startsWith("[")) hp.substringBefore(']').removePrefix("[") else hp.substringBefore(':')
        }.lowercase()
    }.getOrNull()

    /** localhost, loopback and private LAN ranges (10/8, 172.16/12, 192.168/16, *.local). */
    fun isLocalOrLan(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h.endsWith(".local")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || h.count { it == '.' } != 3) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** Cleartext is only allowed to localhost/LAN (also enforced by the network security config for localhost). */
    fun cleartextAllowed(url: String): Boolean =
        !url.startsWith("http://", ignoreCase = true) || host(url)?.let(::isLocalOrLan) == true

    /** "Name: value" per line → headers (blank/invalid lines ignored; Authorization can't be overridden here). */
    fun parseHeaders(text: String): Map<String, String> = text.lines().mapNotNull { line ->
        val i = line.indexOf(':'); if (i <= 0) return@mapNotNull null
        val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim()
        if (k.isEmpty() || v.isEmpty() || k.equals("Authorization", true) || k.any { it.isWhitespace() }) null else k to v
    }.toMap()

    /** OpenRouter attribution headers go only to openrouter.ai. */
    fun isOpenRouterHost(host: String?): Boolean = host == "openrouter.ai" || host?.endsWith(".openrouter.ai") == true

    /** Headers for a custom-provider request: bearer key (if any) + extra headers. */
    fun requestHeaders(apiKey: String?, extra: Map<String, String>): Map<String, String> =
        buildMap { putAll(extra); apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer ${it.trim()}") } }

    /** Model ids from an OpenAI-style GET /models body (`{"data":[{"id":…}]}` or a bare array). */
    fun parseModels(body: String): List<String> = runCatching {
        val el = Json.parseToJsonElement(body)
        val arr = (el as? JsonArray) ?: el.jsonObject["data"]?.jsonArray ?: el.jsonObject["models"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList()).distinct().sorted()
}

data class CustomProviderConfig(val presetId: String, val baseUrl: String, val headers: Map<String, String>) {
    val configured: Boolean get() = baseUrl.isNotBlank()
    val label: String get() = ProviderPresets.byId(presetId).label.substringBefore(" (")
}

/** Custom provider settings; the API key lives in EncryptedSharedPreferences. */
@Singleton
class CustomProviderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("custom_provider", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy {
        try { createSecure() } catch (_: Exception) { context.deleteSharedPreferences(SECURE_FILE); createSecure() }
    }
    private fun createSecure(): SharedPreferences {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, SECURE_FILE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    init { runCatching { migrate(prefs, secure) } }
    /** Bumped on every save so screens re-read per-provider values. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Provider that legacy `custom:<model>` ids (v1.0.44) route to: the one that was active then. */
    val legacyActive: String get() = prefs.getString(KEY_ACTIVE, null) ?: "custom"

    /** Saved base URL override, else the preset's default. */
    fun baseFor(id: String): String = prefs.getString("base_$id", null)?.takeIf { it.isNotBlank() } ?: ProviderPresets.find(id)?.baseUrl.orEmpty()
    fun headersFor(id: String): String = prefs.getString("headers_$id", null) ?: ""
    fun keyFor(id: String): String? = runCatching { secure.getString("key_$id", null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun configFor(id: String) = CustomProviderConfig(id, baseFor(id), ProviderUrls.parseHeaders(headersFor(id)))

    /** Ready to use: cloud presets need a key; local/custom need a base URL. */
    fun isConfigured(id: String): Boolean {
        val p = ProviderPresets.find(id) ?: return false
        return if (p.needsKey) keyFor(id) != null else baseFor(id).isNotBlank() && (p.id != "custom" || prefs.contains("base_$id"))
    }

    /** Saves one provider's values (independent of the others). Blank base = preset default. */
    fun save(presetId: String, baseUrl: String, headersText: String, key: String?) {
        val def = ProviderPresets.find(presetId)?.baseUrl.orEmpty()
        prefs.edit().apply { if (baseUrl.isBlank() || baseUrl == def) remove("base_$presetId") else putString("base_$presetId", baseUrl) }
            .putString("headers_$presetId", headersText).apply()
        if (presetId == "custom" && baseUrl.isNotBlank()) prefs.edit().putString("base_custom", baseUrl).apply()
        runCatching { secure.edit().apply { if (key.isNullOrBlank()) remove("key_$presetId") else putString("key_$presetId", key.trim()) }.apply() }
        _version.value++
    }

    companion object {
        private const val SECURE_FILE = "custom_provider_secure"
        const val KEY_ACTIVE = "active"

        /**
         * v1.0.45: single-slot storage (preset/base/headers + secure "key") → per-provider keys (`base_<id>`,
         * `headers_<id>`, secure `key_<id>`, `active`). Idempotent; old entries are removed after copying.
         */
        fun migrate(prefs: SharedPreferences, secure: SharedPreferences) {
            if (!prefs.contains("preset")) return
            val id = prefs.getString("preset", null) ?: "custom"
            prefs.edit().putString(KEY_ACTIVE, id)
                .putString("base_$id", prefs.getString("base", "") ?: "")
                .putString("headers_$id", prefs.getString("headers", "") ?: "")
                .remove("preset").remove("base").remove("headers").commit()
            secure.getString("key", null)?.let { k -> secure.edit().putString("key_$id", k).remove("key").commit() }
        }
    }
}

/** Calls OpenAI-compatible providers; each `prov:<provider>:<model>` id goes to its own provider. */
@Singleton
class CustomLlmClient @Inject constructor(private val store: CustomProviderStore, private val json: Json) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val cooldowns = HashMap<String, Long>()
    @Synchronized fun cooldownUntil(id: String) = cooldowns[id] ?: 0L
    @Synchronized private fun cool(id: String, until: Long) { cooldowns[id] = until }

    /** Provider id for a model id (legacy `custom:` → the provider that was active in v1.0.44). */
    fun providerOf(modelId: String): String = ModelIds.providerIdOf(modelId) ?: store.legacyActive

    private fun request(providerId: String, url: String): Request.Builder {
        if (!ProviderUrls.cleartextAllowed(url)) throw IOException("http:// only works for this phone (localhost) or *.local names; use https")
        val c = store.configFor(providerId)
        return Request.Builder().url(url).apply {
            ProviderUrls.requestHeaders(store.keyFor(providerId), c.headers).forEach { (k, v) -> header(k, v) }
            header("User-Agent", "Verdroid")
        }
    }

    /** GET {base}models for one provider. Also used as "Test connection". */
    suspend fun listModels(providerId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = store.baseFor(providerId).ifBlank { throw IOException("No server address set") }
            http.newCall(request(providerId, base + "models").get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) throw IOException("the key was rejected (HTTP ${r.code})")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(160)}")
                ProviderUrls.parseModels(body)
            }
        }
    }

    suspend fun complete(models: List<String>, messages: List<ApiMessage>, tools: JsonArray?, cooldownMs: Long,
                         status: suspend (String) -> Unit = {},
                         prepare: suspend (String, List<ApiMessage>) -> List<ApiMessage> = { _, m -> m }): KiloResult {
        var lastError = "No model from your other providers is available"
        for (id in models) {
            val pid = providerOf(id)
            val base = store.baseFor(pid)
            if (base.isBlank()) { lastError = "${ModelIds.label(id)}: ${ProviderPresets.byId(pid).shortName} has no server address (Settings → LLM providers)"; continue }
            val now = System.currentTimeMillis()
            if (cooldownUntil(id) > now) { lastError = "${ModelIds.label(id)} is cooling down"; continue }
            val model = ModelIds.bare(id)
            status("Calling ${ModelIds.label(id)}…")
            val payload = json.encodeToString(ChatRequest.serializer(),
                ChatRequest(model = model, messages = prepare(id, messages), tools = tools, toolChoice = if (tools != null) "auto" else null))
            val (code, body) = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request(pid, base + "chat/completions").post(payload.toRequestBody(JSON)).build()).execute()
                        .use { it.code to it.body?.string() }
                }
            } catch (e: CancellationException) { throw e } catch (e: IOException) {
                lastError = "${ModelIds.label(id)}: ${e.message ?: "network error"}"; cool(id, System.currentTimeMillis() + cooldownMs); continue
            }
            val err = ApiErrorDetector.detect(code, body) ?: run {
                val parsed = body?.let { CompletionParser.parse(it) }
                if (parsed != null) return KiloResult.Success(parsed, ModelIds.label(id), id)
                DetectedApiError(ApiErrorKind.UPSTREAM, 502, "Empty or unparsable completion", null)
            }
            lastError = "${ModelIds.label(id)}: ${err.message}"
            cool(id, System.currentTimeMillis() + if (err.kind == ApiErrorKind.RATE_LIMIT) maxOf(cooldownMs, 60_000L) else cooldownMs)
        }
        return KiloResult.Unavailable(lastError, null, false)
    }

    companion object { private val JSON = "application/json".toMediaType() }
}

/** v1.0.45: rewrites legacy `custom:<model>` entries in Model priority to `prov:<active provider>:<model>`, once. */
@Singleton
class ProviderModelMigration @Inject constructor(
    private val store: CustomProviderStore,
    private val settings: com.verdroid.app.domain.repository.SettingsRepository,
) {
    suspend fun run() {
        val cur = settings.currentModels()
        val next = ModelIds.migrateLegacyCustom(cur, store.legacyActive)
        if (next != cur) settings.setModelPriority(next)
    }
}
