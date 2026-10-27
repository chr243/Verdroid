package com.verdroid.app.ui.chat

import com.verdroid.app.agent.tools.ShownImages
import java.io.File

/**
 * Markdown images in assistant replies: `![caption](src)` outside code blocks becomes an inline image (same card as show_image).
 * src = https URL, or a png/jpg/webp/gif under Documents/Verdroid (relative to it, absolute, or file://). Anything else
 * (http, content://, other folders, `..`) is shown as a placeholder line instead of being loaded.
 */
object InlineImages {
    sealed interface Seg { data class Text(val text: String) : Seg; data class Image(val alt: String, val src: String) : Seg }

    const val ROOT = "/storage/emulated/0/Documents/Verdroid"
    /** Images per message, so a runaway reply can't load dozens of bitmaps. */
    const val MAX_IMAGES = 8
    private val IMG = Regex("""!\[([^\]\n]{0,300})]\(\s*<?([^)\s>]+)>?(?:\s+"[^"\n]*")?\s*\)""")

    fun split(text: String): List<Seg> {
        if (!text.contains("![")) return listOf(Seg.Text(text))
        val out = mutableListOf<Seg>(); val buf = StringBuilder(); var inFence = false; var n = 0
        fun flush() { if (buf.isNotBlank()) out += Seg.Text(buf.toString().trim('\n')); buf.clear() }
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (inFence || !line.contains("![")) { buf.append(line).append('\n'); continue }
            var last = 0
            val code = codeSpans(line)
            for (m in IMG.findAll(line)) {
                if (n >= MAX_IMAGES) break
                if (code.any { m.range.first < it.last + 1 && it.first < m.range.last + 1 }) continue
                buf.append(line, last, m.range.first); flush()
                out += Seg.Image(m.groupValues[1].trim(), m.groupValues[2]); n++; last = m.range.last + 1
            }
            buf.append(line.substring(last)).append('\n')
        }
        flush()
        return out.ifEmpty { listOf(Seg.Text(text)) }
    }

    /** Inline `code` spans (any number of backticks) on one line: images inside stay literal text. */
    internal fun codeSpans(line: String): List<IntRange> = Regex("(`+)(.+?)(?<!`)\\1(?!`)").findAll(line).map { it.range }.toList()

    /** The ref to load, or null when [src] isn't allowed. */
    fun resolve(src: String, alt: String?, root: String = ROOT): ShownImageRef? {
        val cap = alt?.takeIf { it.isNotBlank() }
        if (src.startsWith("https://", true)) return ShownImageRef(null, src, cap)
        if (src.contains("://") && !src.startsWith("file://", true)) return null
        val p = java.net.URLDecoder.decode(src.removePrefix("file://"), "UTF-8").removePrefix("Documents/Verdroid/")
        val rootF = File(root).canonicalFile
        val f = (if (p.startsWith("/")) File(p) else File(rootF, p)).canonicalFile
        if (!f.path.startsWith(rootF.path + File.separator) || f.extension.lowercase() !in ShownImages.EXT) return null
        return ShownImageRef(f.path, null, cap)
    }
}
