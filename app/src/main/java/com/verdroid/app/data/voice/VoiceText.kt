package com.verdroid.app.data.voice

/** Pure helpers for voice input/output (JVM-tested, also used by the `voice` self-test). */
object VoiceText {
    /** Rough cap per TextToSpeech.speak call (engines allow ~4000 chars). */
    const val CHUNK = 3500

    /** Markdown reply → plain speakable text. Code blocks are dropped (replaced by a short note), markup removed. */
    fun forSpeech(markdown: String?): String {
        if (markdown.isNullOrBlank()) return ""
        var t = markdown.replace("\r\n", "\n")
        t = t.replace(Regex("<think>[\\s\\S]*?</think>"), " ")
        t = t.replace(Regex("```[\\s\\S]*?(```|$)"), "\n(code omitted)\n")
        t = t.replace(Regex("~~~[\\s\\S]*?(~~~|$)"), "\n(code omitted)\n")
        t = t.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")      // images → alt text
        t = t.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")       // links → label
        t = t.replace(Regex("<https?://[^>]+>"), "link")
        t = t.replace(Regex("https?://\\S+"), "link")
        t = t.replace(Regex("`([^`\\n]*)`"), "$1")
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*>\\s?"), "")
        t = t.replace(Regex("(?m)^\\s*([-*+]|\\d+[.)])\\s+"), "")
        t = t.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$"), "")       // table separator rows
        t = t.replace(Regex("(?m)^\\s*([-*_]\\s*){3,}$"), "")          // horizontal rules
        t = t.replace("|", ", ")
        t = t.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        t = t.replace(Regex("(?<![\\w*])[*_](\\S(?:.*?\\S)?)[*_](?![\\w*])"), "$1")
        t = t.replace(Regex("~~(.+?)~~"), "$1")
        t = t.replace(Regex("<[^>\\n]+>"), "")
        t = t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").replace(Regex("\\n{2,}"), "\n")
        return t.lines().joinToString("\n") { it.trim().trim(',').trim() }.trim()
    }

    /** Splits text for TTS at sentence/line boundaries, each piece ≤ [max]. */
    fun chunks(text: String, max: Int = CHUNK): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>(); val cur = StringBuilder()
        for (part in text.split(Regex("(?<=[.!?\\n])\\s+"))) {
            var p = part
            while (p.length > max) { if (cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }; out += p.take(max); p = p.drop(max) }
            if (cur.length + p.length + 1 > max && cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' '); cur.append(p)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    /** Recognized text goes into the input: appended to what the user already typed. */
    fun merge(existing: String, recognized: String): String {
        val r = recognized.trim(); if (r.isEmpty()) return existing
        return if (existing.isBlank()) r else existing.trimEnd() + " " + r
    }

    /** Auto-send only when the setting is on and the input field was empty (never sends half-typed text by surprise). */
    fun shouldAutoSend(autoSend: Boolean, existing: String, recognized: String): Boolean =
        autoSend && existing.isBlank() && recognized.isNotBlank()

    /** Hands-free: listen again after a reply was spoken, only while speaking is on and nothing is running. */
    fun listenAfterSpeech(handsFree: Boolean, speakReplies: Boolean, generating: Boolean, interrupted: Boolean): Boolean =
        handsFree && speakReplies && !generating && !interrupted

    /** Speak once per finished run: speaking on, run seen and over, newest row is an assistant text reply not spoken yet. */
    fun shouldSpeak(speakReplies: Boolean, generating: Boolean, sawRun: Boolean, newestIsReply: Boolean, newestId: Long?, spokenId: Long?): Boolean =
        speakReplies && !generating && sawRun && newestIsReply && newestId != null && newestId != spokenId

    /** User-facing text for SpeechRecognizer error codes. */
    fun errorMessage(code: Int): String = when (code) {
        1, 2 -> "Network problem during speech recognition."           // NETWORK_TIMEOUT, NETWORK
        3 -> "Microphone could not be used."                            // AUDIO
        5 -> "Speech recognition failed."                               // CLIENT
        6, 7 -> "Didn't catch that. Tap the mic and try again."         // SPEECH_TIMEOUT, NO_MATCH
        8 -> "Speech recognizer is busy. Try again in a moment."        // RECOGNIZER_BUSY
        9 -> "Microphone permission is needed for voice input."         // INSUFFICIENT_PERMISSIONS
        12, 13 -> "Your device's language isn't supported for speech recognition." // LANGUAGE_*
        else -> "Speech recognition error ($code)."
    }
    /** Codes that just mean "nothing heard" (no error toast in hands-free mode). */
    fun isSilence(code: Int) = code == 6 || code == 7
}
