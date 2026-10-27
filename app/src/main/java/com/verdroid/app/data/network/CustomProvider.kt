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
import kotlinx.serialization.builtins.ListSerializer
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
data class ProviderPreset(val id: String, val label: String, val baseUrl: String, val needsKey: Boolean, val note: String = "", val keyUrl: String? = null,
                          /** A provider the user added (Settings → LLM providers → + Add provider). */
                          val userDefined: Boolean = false) {
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
    /** Built-in presets shown as their own chips (the legacy "Custom" slot is listed with the user's providers). */
    val BUILT_IN = ALL.filter { it.id != "custom" }
}

/** Display names of the user's own providers (kept in sync by [CustomProviderStore]) for labels like "llama3 (My server)". */
object ProviderNames {
    @Volatile private var names: Map<String, String> = emptyMap()
    fun update(map: Map<String, String>) { names = map }
    fun short(id: String): String = names[id] ?: ProviderPresets.find(id)?.shortName ?: if (id == "custom") "Custom" else id
}

/** One saved key of an OpenAI-compatible provider (the secret only lives in EncryptedSharedPreferences). */
@kotlinx.serialization.Serializable
data class ProviderKey(val id: String, val label: String, val key: String) {
    val masked: String get() = if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)
    override fun toString() = "ProviderKey(id=$id, label=$label)"
}

@kotlinx.serialization.Serializable
data class SavedProvider(val id: String, val name: String)

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
    val label: String get() = ProviderNames.short(presetId)
}

/**
 * Settings of OpenAI-compatible providers: built-in presets plus any number of the user's own ("custom") providers.
 * Base URL / headers in plain prefs (`base_<id>`, `headers_<id>`); keys as a JSON list in EncryptedSharedPreferences
 * (`keys_<id>`). The user's providers are listed in `customs_v1`; the pre-v1.0.71 single "Custom" slot keeps id `custom`.
 */
@Singleton
class CustomProviderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("custom_provider", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy {
        try { createSecure() } catch (_: Exception) { context.deleteSharedPreferences(SECURE_FILE); com.verdroid.app.data.secure.KeyWipeNotice.record(context, "custom provider key"); createSecure() }
    }
    private fun createSecure(): SharedPreferences {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, SECURE_FILE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    init { runCatching { migrate(prefs, secure) }; runCatching { migrateV2(prefs, secure) }; publishNames() }
    /** Bumped on every save so screens re-read per-provider values. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { publishNames(); _version.value++ }

    /** Provider that legacy `custom:<model>` ids (v1.0.44) route to: the one that was active then. */
    val legacyActive: String get() = prefs.getString(KEY_ACTIVE, null) ?: "custom"

    /** The user's own providers, in the order they were added. */
    fun customs(): List<ProviderPreset> = readCustoms(prefs).map { custom(it) }
    /** Built-in presets followed by the user's providers (what Settings and the model picker show). */
    fun allProviders(): List<ProviderPreset> = ProviderPresets.BUILT_IN + customs()
    /** Preset or user provider for [id]; null if unknown (e.g. a removed provider). */
    fun provider(id: String): ProviderPreset? = readCustoms(prefs).firstOrNull { it.id == id }?.let(::custom) ?: ProviderPresets.find(id)?.takeIf { it.id != "custom" }

    private fun custom(s: SavedProvider) = ProviderPreset(s.id, s.name, "", false, "Any service with an OpenAI-compatible API", userDefined = true)
    private fun publishNames() = ProviderNames.update(readCustoms(prefs).associate { it.id to it.name })

    /** Adds a provider; returns its id. */
    fun addCustom(name: String, baseUrl: String, headersText: String): String {
        val list = readCustoms(prefs)
        val id = newCustomId(list.map { it.id }.toSet())
        writeCustoms(prefs, list + SavedProvider(id, name.trim().ifBlank { "Provider ${list.size + 1}" }))
        prefs.edit().putString("base_$id", baseUrl).putString("headers_$id", headersText).apply()
        bump(); return id
    }

    fun renameCustom(id: String, name: String) {
        writeCustoms(prefs, readCustoms(prefs).map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it }); bump()
    }

    /** Removes a user provider with its address, headers and keys (built-in presets can't be removed). */
    fun removeCustom(id: String) {
        val list = readCustoms(prefs); if (list.none { it.id == id }) return
        writeCustoms(prefs, list.filterNot { it.id == id })
        prefs.edit().remove("base_$id").remove("headers_$id").apply()
        runCatching { secure.edit().remove("keys_$id").remove("key_$id").apply() }
        bump()
    }

    /** Saved base URL override, else the preset's default. */
    fun baseFor(id: String): String = prefs.getString("base_$id", null)?.takeIf { it.isNotBlank() } ?: ProviderPresets.find(id)?.baseUrl.orEmpty()
    fun headersFor(id: String): String = prefs.getString("headers_$id", null) ?: ""

    /** All keys of a provider, in priority order (first = primary). */
    fun keysFor(id: String): List<ProviderKey> = runCatching { readKeys(secure, id) }.getOrDefault(emptyList())
    /** First key (kept for callers that only need one, e.g. Test connection). */
    fun keyFor(id: String): String? = keysFor(id).firstOrNull()?.key

    fun addKey(id: String, label: String, key: String) {
        val k = key.trim(); if (k.isEmpty()) return
        val list = keysFor(id); if (list.any { it.key == k }) return
        writeKeys(secure, id, list + ProviderKey(java.util.UUID.randomUUID().toString().take(8), label.trim(), k)); bump()
    }
    fun removeKey(id: String, keyId: String) { writeKeys(secure, id, keysFor(id).filterNot { it.id == keyId }); bump() }
    fun renameKey(id: String, keyId: String, label: String) { writeKeys(secure, id, keysFor(id).map { if (it.id == keyId) it.copy(label = label.trim()) else it }); bump() }
    fun moveKey(id: String, keyId: String, delta: Int) {
        val list = keysFor(id).toMutableList(); val i = list.indexOfFirst { it.id == keyId }; val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i)); writeKeys(secure, id, list); bump()
    }

    fun configFor(id: String) = CustomProviderConfig(id, baseFor(id), ProviderUrls.parseHeaders(headersFor(id)))

    /** Ready to use: cloud presets need a key; local/custom need a base URL. */
    fun isConfigured(id: String): Boolean {
        val p = provider(id) ?: return false
        if (p.userDefined) return prefs.getString("base_$id", null).orEmpty().isNotBlank()
        return if (p.needsKey) keysFor(id).isNotEmpty() else baseFor(id).isNotBlank()
    }

    /** Saves one provider's address and headers (keys are managed with [addKey] etc.). Blank base = preset default. */
    fun save(presetId: String, baseUrl: String, headersText: String) {
        val def = ProviderPresets.find(presetId)?.takeIf { it.id != "custom" }?.baseUrl.orEmpty()
        prefs.edit().apply { if (baseUrl.isBlank() || baseUrl == def) remove("base_$presetId") else putString("base_$presetId", baseUrl) }
            .putString("headers_$presetId", headersText).apply()
        bump()
    }

    companion object {
        private const val SECURE_FILE = "custom_provider_secure"
        const val KEY_ACTIVE = "active"
        const val KEY_CUSTOMS = "customs_v1"
        private val json = Json { ignoreUnknownKeys = true }

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

        /**
         * v1.0.71: one key per provider (`key_<id>`) → key list (`keys_<id>`, the old key becomes "Key 1"), and the single
         * "Custom" slot (if it had an address) becomes the first entry of the user's provider list, keeping id `custom` so
         * `prov:custom:<model>` entries in Model priority keep working. Idempotent; the old key is removed only after the
         * list was written.
         */
        fun migrateV2(prefs: SharedPreferences, secure: SharedPreferences) {
            secure.all.keys.filter { it.startsWith("key_") }.forEach { name ->
                val id = name.removePrefix("key_")
                val old = secure.getString(name, null)?.trim().orEmpty()
                val list = readKeys(secure, id)
                if (old.isNotEmpty() && list.none { it.key == old }) {
                    secure.edit().putString("keys_$id", json.encodeToString(ListSerializer(ProviderKey.serializer()),
                        listOf(ProviderKey("k1", "Key 1", old)) + list)).commit()
                }
                if (readKeys(secure, id).any { it.key == old } || old.isEmpty()) secure.edit().remove(name).commit()
            }
            if (!prefs.contains(KEY_CUSTOMS)) {
                val legacy = prefs.getString("base_custom", null).orEmpty().isNotBlank() || secure.contains("keys_custom")
                writeCustoms(prefs, if (legacy) listOf(SavedProvider("custom", "Custom")) else emptyList())
            }
        }

        fun readCustoms(prefs: SharedPreferences): List<SavedProvider> = runCatching {
            json.decodeFromString(ListSerializer(SavedProvider.serializer()), prefs.getString(KEY_CUSTOMS, null) ?: "[]")
        }.getOrDefault(emptyList())
        fun writeCustoms(prefs: SharedPreferences, list: List<SavedProvider>) {
            prefs.edit().putString(KEY_CUSTOMS, json.encodeToString(ListSerializer(SavedProvider.serializer()), list)).commit()
        }
        fun readKeys(secure: SharedPreferences, id: String): List<ProviderKey> = runCatching {
            json.decodeFromString(ListSerializer(ProviderKey.serializer()), secure.getString("keys_$id", null) ?: "[]")
        }.getOrDefault(emptyList())
        fun writeKeys(secure: SharedPreferences, id: String, list: List<ProviderKey>) {
            secure.edit().apply { if (list.isEmpty()) remove("keys_$id") else putString("keys_$id", json.encodeToString(ListSerializer(ProviderKey.serializer()), list)) }.commit()
        }
        /** Ids of user providers: "c" + 6 hex chars; never contains ':' (model ids are `prov:<id>:<model>`). */
        fun newCustomId(taken: Set<String>): String {
            while (true) { val id = "c" + java.util.UUID.randomUUID().toString().replace("-", "").take(6); if (id !in taken && ProviderPresets.find(id) == null) return id }
        }
    }
}

/** Calls OpenAI-compatible providers; each `prov:<provider>:<model>` id goes to its own provider, rotating its keys. */
@Singleton
class CustomLlmClient @Inject constructor(
    private val store: CustomProviderStore,
    private val json: Json,
    private val tracker: KeyHealthTracker,
    private val rateLimitLog: com.verdroid.app.domain.repository.RateLimitRepository,
) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val cooldowns = HashMap<String, Long>()
    /** Model cooldown (server errors), or when every key of its provider is cooling down / locked for it. */
    @Synchronized fun cooldownUntil(id: String): Long {
        val own = cooldowns[id] ?: 0L
        val pid = providerOf(id); val keys = store.keysFor(pid); if (keys.isEmpty()) return maxOf(own, tracker.status(KeyHealthTracker.scopeOf(pid), KeyHealthTracker.NO_KEY).coolUntil)
        val now = System.currentTimeMillis(); val m = ModelIds.bare(id)
        val free = keys.map { k -> tracker.status(KeyHealthTracker.scopeOf(pid), k.id).let { s -> maxOf(s.coolUntil, s.modelLocks[m] ?: 0) } }
        return maxOf(own, free.minOrNull()?.takeIf { it > now } ?: 0L)
    }
    @Synchronized private fun cool(id: String, until: Long) { cooldowns[id] = until }

    /** Provider id for a model id (legacy `custom:` → the provider that was active in v1.0.44). */
    fun providerOf(modelId: String): String = ModelIds.providerIdOf(modelId) ?: store.legacyActive

    private fun request(providerId: String, url: String, key: String?): Request.Builder {
        if (!ProviderUrls.cleartextAllowed(url)) throw IOException("http:// only works for this phone (localhost) or *.local names; use https")
        val c = store.configFor(providerId)
        return Request.Builder().url(url).apply {
            ProviderUrls.requestHeaders(key, c.headers).forEach { (k, v) -> header(k, v) }
            header("User-Agent", "Verdroid")
        }
    }

    /** GET {base}models for one provider (first key that isn't cooling down). Also used as "Test connection". */
    suspend fun listModels(providerId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = store.baseFor(providerId).ifBlank { throw IOException("No server address set") }
            val keys = store.keysFor(providerId)
            val key = tracker.health.order(KeyHealthTracker.scopeOf(providerId), keys, { it.id }, false).firstOrNull() ?: keys.firstOrNull()
            http.newCall(request(providerId, base + "models", key?.key).get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 401 || r.code == 403) throw IOException("the key was rejected (HTTP ${r.code})")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(160)}")
                ProviderUrls.parseModels(body)
            }
        }
    }

    suspend fun complete(models: List<String>, messages: List<ApiMessage>, tools: JsonArray?, cooldownMs: Long,
                         status: suspend (String) -> Unit = {},
                         prepare: suspend (String, List<ApiMessage>) -> List<ApiMessage> = { _, m -> m },
                         taskId: Long? = null): KiloResult {
        var lastError = "No model from your other providers is available"
        var rateLimited = false
        var resumeAt: Long? = null
        fun resume(t: Long?) { if (t != null && t > System.currentTimeMillis()) resumeAt = minOf(resumeAt ?: t, t) }
        for (id in models) {
            val pid = providerOf(id)
            val name = ProviderNames.short(pid)
            val breakerId = "prov:$pid"
            val base = store.baseFor(pid)
            if (base.isBlank()) { lastError = "${ModelIds.label(id)}: $name has no server address (Settings → LLM providers)"; continue }
            val now = System.currentTimeMillis()
            if ((cooldowns[id] ?: 0L) > now) { lastError = "${ModelIds.label(id)} is cooling down"; continue }
            if (!tracker.breaker.allow(breakerId)) { lastError = "$name is failing repeatedly; skipped for now"; continue }
            val model = ModelIds.bare(id)
            val scope = KeyHealthTracker.scopeOf(pid)
            val all = store.keysFor(pid)
            if (all.isEmpty() && store.provider(pid)?.needsKey == true) { lastError = "${ModelIds.label(id)}: no API key for $name (Settings → LLM providers)"; continue }
            val slots: List<ProviderKey?> = if (all.isEmpty()) {
                if (tracker.status(scope, KeyHealthTracker.NO_KEY).coolingDown(now)) emptyList() else listOf(null)
            } else tracker.health.order(scope, all.filter { !tracker.status(scope, it.id).modelLocked(model, now) }, { it.id }, tracker.roundRobin.value)
            if (slots.isEmpty()) {
                rateLimited = true; lastError = "${ModelIds.label(id)}: every $name key is cooling down"
                resume(tracker.health.nextFree(scope, all.map { it.id }.ifEmpty { listOf(KeyHealthTracker.NO_KEY) })); continue
            }
            status("Calling ${ModelIds.label(id)}…")
            val payload = json.encodeToString(ChatRequest.serializer(),
                ChatRequest(model = model, messages = prepare(id, messages), tools = tools, toolChoice = if (tools != null) "auto" else null))
            keyLoop@ for ((i, key) in slots.withIndex()) {
                val kid = key?.id ?: KeyHealthTracker.NO_KEY
                val keyName = key?.let { it.label.ifBlank { "Key ${all.indexOf(it) + 1}" } } ?: "no key"
                tracker.health.onRequest(scope, kid)
                val resp = try {
                    withContext(Dispatchers.IO) {
                        http.newCall(request(pid, base + "chat/completions", key?.key).post(payload.toRequestBody(JSON)).build()).execute()
                            .use { r -> Triple(r.code, r.body?.string(), r.headers) }
                    }
                } catch (e: CancellationException) { throw e } catch (e: IOException) {
                    lastError = "${ModelIds.label(id)}: ${e.message ?: "network error"}"
                    tracker.breaker.failure(breakerId); tracker.changed()
                    cool(id, System.currentTimeMillis() + cooldownMs); break@keyLoop
                }
                val (code, body, headers) = resp
                val err = ApiErrorDetector.detect(code, body) ?: run {
                    val parsed = body?.let { CompletionParser.parse(it) }
                    if (parsed != null) {
                        tracker.health.onSuccess(scope, kid); tracker.breaker.success(breakerId); tracker.changed()
                        return KiloResult.Success(parsed, ModelIds.label(id), id)
                    }
                    DetectedApiError(ApiErrorKind.UPSTREAM, 502, "Empty or unparsable completion", null)
                }
                lastError = "${ModelIds.label(id)}: ${err.message}"
                val fault = KeyPolicy.classify(err)
                val retry = RetryAfter.parse({ headers[it] }, body)
                val next = if (i < slots.lastIndex) "next $name key" else "next model"
                when (fault) {
                    KeyFault.RATE_LIMIT, KeyFault.CREDITS, KeyFault.INVALID, KeyFault.MODEL -> {
                        val wait = tracker.health.onFailure(scope, kid, model, fault, err.message, retry, if (fault == KeyFault.RATE_LIMIT) minOf(cooldownMs, 60_000L) else 0)
                        tracker.changed()
                        if (fault == KeyFault.RATE_LIMIT) { rateLimited = true; resume(System.currentTimeMillis() + wait) }
                        val what = when (fault) { KeyFault.MODEL -> "$model locked on this key ${wait / 60_000} min"; else -> "key cooling down ${wait / 1000}s" }
                        logEvent(taskId, id, "$name · $keyName", code, err.errorType ?: fault.name.lowercase(), err.message, "$what → $next")
                        continue@keyLoop
                    }
                    KeyFault.SERVER -> { tracker.breaker.failure(breakerId); tracker.changed(); cool(id, System.currentTimeMillis() + cooldownMs); break@keyLoop }
                    KeyFault.OTHER -> { cool(id, System.currentTimeMillis() + cooldownMs); break@keyLoop }
                }
            }
        }
        return KiloResult.Unavailable(lastError, if (rateLimited) resumeAt else null, rateLimited)
    }

    private suspend fun logEvent(taskId: Long?, model: String, keyLabel: String, code: Int, type: String, message: String, outcome: String) {
        runCatching {
            rateLimitLog.log(com.verdroid.app.domain.model.RateLimitEvent(0, System.currentTimeMillis(), taskId, model, keyLabel, code,
                null, null, null, type, message.take(300), outcome))
        }
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
