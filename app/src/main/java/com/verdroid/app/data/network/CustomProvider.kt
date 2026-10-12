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
data class ProviderPreset(val id: String, val label: String, val baseUrl: String, val needsKey: Boolean, val note: String = "")

object ProviderPresets {
    val ALL = listOf(
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1/", true),
        ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1/", true),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1/", true),
        ProviderPreset("together", "Together", "https://api.together.xyz/v1/", true),
        ProviderPreset("ollama", "Ollama (local)", "http://localhost:11434/v1/", false, "Ollama on this phone (Termux) or your LAN IP"),
        ProviderPreset("lmstudio", "LM Studio (local)", "http://localhost:1234/v1/", false, "LM Studio server; use the PC's LAN IP"),
        ProviderPreset("custom", "Custom (OpenAI-compatible)", "", false),
    )
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.last()
}

/** Pure URL / header rules (unit-tested). */
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
    private val _config = MutableStateFlow(read())
    val config: StateFlow<CustomProviderConfig> = _config.asStateFlow()

    private fun read() = CustomProviderConfig(prefs.getString("preset", "custom") ?: "custom", prefs.getString("base", "") ?: "",
        ProviderUrls.parseHeaders(prefs.getString("headers", "") ?: ""))

    val headersText: String get() = prefs.getString("headers", "") ?: ""
    var apiKey: String?
        get() = runCatching { secure.getString("key", null) }.getOrNull()?.takeIf { it.isNotBlank() }
        set(v) { runCatching { secure.edit().apply { if (v.isNullOrBlank()) remove("key") else putString("key", v.trim()) }.apply() } }

    fun save(presetId: String, baseUrl: String, headersText: String) {
        prefs.edit().putString("preset", presetId).putString("base", baseUrl).putString("headers", headersText).apply()
        _config.value = read()
    }

    companion object { private const val SECURE_FILE = "custom_provider_secure" }
}

/** Calls the custom OpenAI-compatible endpoint (`custom:<model>` ids in the priority list). */
@Singleton
class CustomLlmClient @Inject constructor(private val store: CustomProviderStore, private val json: Json) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val cooldowns = HashMap<String, Long>()
    @Synchronized fun cooldownUntil(id: String) = cooldowns[id] ?: 0L
    @Synchronized private fun cool(id: String, until: Long) { cooldowns[id] = until }

    private fun request(url: String): Request.Builder {
        val c = store.config.value
        if (!ProviderUrls.cleartextAllowed(url)) throw IOException("http:// is only allowed for localhost/LAN; use https")
        return Request.Builder().url(url).apply {
            ProviderUrls.requestHeaders(store.apiKey, c.headers).forEach { (k, v) -> header(k, v) }
            header("User-Agent", "Verdroid")
        }
    }

    /** GET {base}models; empty list when unsupported. */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = store.config.value.baseUrl.ifBlank { throw IOException("No base URL set") }
            http.newCall(request(base + "models").get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(160)}")
                ProviderUrls.parseModels(body)
            }
        }
    }

    suspend fun complete(models: List<String>, messages: List<ApiMessage>, tools: JsonArray?, cooldownMs: Long,
                         status: suspend (String) -> Unit = {},
                         prepare: suspend (String, List<ApiMessage>) -> List<ApiMessage> = { _, m -> m }): KiloResult {
        val c = store.config.value
        if (!c.configured) return KiloResult.Unavailable("Custom provider has no base URL (Settings → LLM provider)", null, false)
        var lastError = "No custom model available"
        for (id in models) {
            val now = System.currentTimeMillis()
            if (cooldownUntil(id) > now) { lastError = "${ModelIds.label(id)} is cooling down"; continue }
            val model = ModelIds.bare(id)
            status("Calling ${ModelIds.label(id)}…")
            val payload = json.encodeToString(ChatRequest.serializer(),
                ChatRequest(model = model, messages = prepare(id, messages), tools = tools, toolChoice = if (tools != null) "auto" else null))
            val (code, body) = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request(c.baseUrl + "chat/completions").post(payload.toRequestBody(JSON)).build()).execute()
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
