package com.verdroid.app.agent.tools

import com.verdroid.app.data.storage.SharedFolder
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pure helpers for show_image: what may be shown, how it's sniffed, where downloaded images are cached. Local files only
 * inside Documents/Verdroid (SharedFolderSandbox rules: no traversal) or the app's own chart folder; https URLs only
 * (http only when [allowHttp], used by tests); no loopback/private hosts. Only png/jpg/webp/gif, by magic bytes.
 */
object ShownImages {
    const val MAX_BYTES = 15L * 1024 * 1024
    val EXT = setOf("png", "jpg", "jpeg", "webp", "gif")

    /** "png" | "jpg" | "webp" | "gif" from the first bytes, or null (not an allowed image). */
    fun sniff(h: ByteArray): String? = when {
        h.size >= 8 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte() -> "png"
        h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> "jpg"
        h.size >= 6 && String(h, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "gif"
        h.size >= 12 && String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        else -> null
    }

    /** First [n] bytes (InputStream.readNBytes needs API 33). */
    fun head(i: java.io.InputStream, n: Int = 16): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(b, off, n - off); if (r < 0) break; off += r }
        return b.copyOf(off)
    }

    fun mime(kind: String) = if (kind == "jpg") "image/jpeg" else "image/$kind"

    fun hostAllowed(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || '.' !in h && ':' !in h) return false
        if (h.contains(':')) return !(h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd"))
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4) {
            if (p[0] == 10 || p[0] == 127 || p[0] == 0 || (p[0] == 169 && p[1] == 254) || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)) return false
        }
        return true
    }

    /** Where the bytes of [url] are cached (the message stores only the URL). */
    fun cacheFile(cacheDir: File, url: String): File =
        File(File(cacheDir, "shown-images"), MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32))

    val CLIENT: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(false).build()
    }

    /** Downloads [url] into the cache (size-capped, type-checked); returns the cached file or an error message. */
    fun fetch(url: String, cacheDir: File, client: OkHttpClient = CLIENT, allowHttp: Boolean = false): Result<File> = runCatching {
        val u = with(okhttp3.HttpUrl.Companion) { url.toHttpUrlOrNull() } ?: error("not a valid URL")
        if (u.scheme != "https" && !(allowHttp && u.scheme == "http")) error("only https:// image URLs are allowed")
        if (!allowHttp && !hostAllowed(u.host)) error("local/private hosts are not allowed")
        val cached = cacheFile(cacheDir, url)
        if (cached.isFile && cached.length() > 0) return@runCatching cached
        client.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Android) Verdroid").header("Accept", "image/*").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("empty response")
            if (body.contentLength() > MAX_BYTES) error("image too large (max 15 MB)")
            cached.parentFile?.mkdirs()
            val tmp = File(cached.path + ".part")
            var total = 0L
            body.byteStream().use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(16 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > MAX_BYTES) { tmp.delete(); error("image too large (max 15 MB)") }; o.write(buf, 0, n) }
            } }
            val first = tmp.inputStream().use { head(it) }
            if (sniff(first) == null) { tmp.delete(); error("not a png/jpg/webp/gif image") }
            tmp.renameTo(cached)
            cached
        }
    }
}

class ShowImageTool(
    private val folder: SharedFolder,
    private val chartDir: File,
    private val cacheDir: File,
    private val client: OkHttpClient = ShownImages.CLIENT,
    private val allowHttp: Boolean = false,
) : AgentTool {
    private val sandbox = SharedFolderSandbox(folder)
    override val name = NAME
    override val description = "Show an image to the user inline in the chat (tap = fullscreen with pinch-zoom, share). source = a file under " +
        "Documents/Verdroid (Output/, Input/, Repos/<name>/… ; relative to it or absolute) or an https:// image URL. png/jpg/webp/gif, max 15 MB. " +
        "Use it for screenshots, generated images, photos, diagrams you saved to Output/. Add a short caption. Charts: use the chart tool. " +
        "You can also embed images directly in your reply text with markdown ![caption](Output/plot.png) or ![caption](https://…/x.png) " +
        "(same rules: https or files under Documents/Verdroid; up to 8 per reply)."
    override val parameters = schema(listOf("source"),
        "source" to prop("string", "Path under Documents/Verdroid (e.g. Output/plot.png) or an https:// image URL"),
        "caption" to prop("string", "Short caption shown under the image"))

    override suspend fun execute(args: JsonObject): String {
        val src = args.str("source")?.trim().orEmpty().ifEmpty { return errorJson("source is required") }
        val caption = args.str("caption")?.take(300)
        if (src.startsWith("http://", true) || src.startsWith("https://", true)) {
            val f = ShownImages.fetch(src, cacheDir, client, allowHttp).getOrElse { return errorJson("show_image: ${it.message}") }
            val kind = f.inputStream().use { ShownImages.sniff(ShownImages.head(it)) } ?: return errorJson("show_image: not an image")
            return result("url", src, kind, f.length(), caption)
        }
        val file = try { resolveLocal(src) } catch (e: SecurityException) { return errorJson("show_image: ${e.message}") }
            ?: return errorJson("All files access is not granted, so Documents/Verdroid can't be read (Settings > Tools > Shared folder).")
        if (!file.isFile) return errorJson("show_image: file not found: $src")
        if (file.extension.lowercase() !in ShownImages.EXT) return errorJson("show_image: only png, jpg, webp or gif files")
        if (file.length() > ShownImages.MAX_BYTES) return errorJson("show_image: image too large (max 15 MB)")
        val kind = file.inputStream().use { ShownImages.sniff(ShownImages.head(it)) } ?: return errorJson("show_image: not a valid png/jpg/webp/gif")
        return result("file", file.path, kind, file.length(), caption)
    }

    /** Documents/Verdroid (no traversal, repo shorthand) or the app's chart folder; null = no storage access. */
    internal fun resolveLocal(src: String): File? {
        val p = src.replace('\\', '/')
        if (p.startsWith("/")) {
            val c = File(p).canonicalFile
            val charts = chartDir.canonicalFile
            if (c.path.startsWith(charts.path + File.separator)) return c
        }
        if (!folder.hasAccess()) return null
        return sandbox.resolve(src)
    }

    private fun result(kind: String, source: String, type: String, bytes: Long, caption: String?) = buildJsonObject {
        put("ok", true); put("kind", kind); put(if (kind == "url") "url" else "path", source); put("type", type); put("bytes", bytes)
        caption?.let { put("caption", it) }
        put("note", "The image is shown to the user in the chat. Don't describe it unless asked.")
    }.toString()

    companion object { const val NAME = "show_image" }
}
