package com.verdroid.app.data.localllm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One imported GGUF file. [id] is what goes after `local:` in Model priority. */
@Serializable
data class LocalModel(val id: String, val name: String, val path: String, val sizeBytes: Long)

/** Pure helpers (JVM-tested). */
object LocalModels {
    /** "Qwen3-4B-Instruct-Q4_K_M.gguf" → "qwen3-4b-instruct-q4_k_m"; unique against [taken]. */
    fun idFor(fileName: String, taken: Set<String>): String {
        val base = fileName.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF").lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifBlank { "model" }.take(64)
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    /** GGUF files start with the ASCII magic "GGUF". */
    fun isGguf(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'G'.code.toByte() && head[1] == 'G'.code.toByte() &&
        head[2] == 'U'.code.toByte() && head[3] == 'F'.code.toByte()

    /** Default thread count: big cores only, roughly half the cores, 2..6. */
    fun defaultThreads(cores: Int): Int = (cores / 2).coerceIn(2, 6)

    val CONTEXT_SIZES = listOf(2048, 4096, 8192, 16384)

    /**
     * Rough RAM need for a model: weights (mmap, ≈ file size) + KV cache + overhead. Refused when it is clearly more
     * than what the phone has free, so a load never takes the app down (OOM → Unavailable → next model).
     */
    fun fitsInRam(fileBytes: Long, nCtx: Int, availBytes: Long): Boolean {
        val kv = nCtx.toLong() * 160_000L // ~0.15 MB/token for 3–4B models at f16 KV
        val need = fileBytes + kv + 300L * 1024 * 1024
        return need <= availBytes + fileBytes / 3 // mmap pages can be evicted; allow some slack
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> "${bytes / (1L shl 20)} MB"
    }
}

/**
 * Imported on-device models (copied into app-private storage `files/models/`) and the engine settings.
 * Model files never leave the app's own folder.
 */
@Singleton
class LocalModelStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("local_llm", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow(read())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private fun read(): List<LocalModel> = runCatching {
        json.decodeFromString(ListSerializer(LocalModel.serializer()), prefs.getString("models", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun write(list: List<LocalModel>) {
        prefs.edit().putString("models", json.encodeToString(ListSerializer(LocalModel.serializer()), list)).apply()
        _models.value = list
    }

    fun find(id: String): LocalModel? = _models.value.firstOrNull { it.id == id }

    var contextSize: Int
        get() = prefs.getInt("n_ctx", 4096)
        set(v) { prefs.edit().putInt("n_ctx", v).apply() }
    var threads: Int
        get() = prefs.getInt("threads", LocalModels.defaultThreads(Runtime.getRuntime().availableProcessors()))
        set(v) { prefs.edit().putInt("threads", v.coerceIn(1, 16)).apply() }
    /** "Use GPU" switch (Vulkan), off by default. */
    var useGpu: Boolean
        get() = prefs.getBoolean("use_gpu", false)
        set(v) { prefs.edit().putBoolean("use_gpu", v).apply() }
    /** GPU layers to offload: −1 = all. */
    var gpuLayers: Int
        get() = prefs.getInt("gpu_layers", -1)
        set(v) { prefs.edit().putInt("gpu_layers", v).apply() }
    /** Why the GPU was switched off automatically (crash / failed probe); null = not blocked. */
    var gpuBlocked: String?
        get() = prefs.getString("gpu_blocked", null)
        set(v) { prefs.edit().apply { if (v == null) remove("gpu_blocked") else putString("gpu_blocked", v) }.apply() }
    /** Last probe summary shown in the panel ("Adreno (TM) 740 · OK"). */
    var gpuProbe: String?
        get() = prefs.getString("gpu_probe", null)
        set(v) { prefs.edit().putString("gpu_probe", v).apply() }

    /** Crash sentinel: written right before risky GPU work, removed when it finishes. Survives a native crash. */
    val gpuSentinel: File get() = File(context.filesDir, "gpu_in_progress")

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 1024)
        set(v) { prefs.edit().putInt("max_tokens", v.coerceIn(64, 8192)).apply() }

    /** Copies a picked .gguf into `files/models/` (never overwrites: a new unique name is used). */
    suspend fun import(uri: Uri, onProgress: (Long, Long) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val input = cr.openInputStream(uri) ?: throw IOException("Can't open the file")
        input.use { ins ->
            val head = ByteArray(4)
            val bin = java.io.BufferedInputStream(ins, 1 shl 20)
            bin.mark(8)
            if (bin.read(head) != 4 || !LocalModels.isGguf(head)) throw IOException("Not a GGUF model file")
            bin.reset()
            val id = LocalModels.idFor(name, _models.value.map { it.id }.toSet() + (dir.list()?.map { it.removeSuffix(".gguf") } ?: emptyList()))
            val tmp = File(dir, "$id.gguf.part")
            val out = File(dir, "$id.gguf")
            try {
                tmp.outputStream().use { os ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = bin.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n); done += n
                        onProgress(done, size)
                    }
                }
                if (!tmp.renameTo(out)) throw IOException("Couldn't save the model")
            } catch (e: Throwable) {
                tmp.delete() // our own partial copy in app-private storage
                throw e
            }
            val m = LocalModel(id, name.removeSuffix(".gguf"), out.absolutePath, out.length())
            write(_models.value + m)
            m
        }
    }

    /** Removes the entry and its copied file (only files inside [dir] are ever deleted). */
    fun remove(id: String) {
        val m = find(id) ?: return
        val f = File(m.path)
        if (f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) f.delete()
        write(_models.value.filter { it.id != id })
    }
}
