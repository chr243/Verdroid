package com.verdroid.app.data.network

import kotlinx.serialization.json.*

/** Values of the X-RateLimit-* response headers. [resetAtMs] is normalised to Unix milliseconds. */
data class RateLimitHeaders(
    val limit: Int?,
    val remaining: Int?,
    val resetAtMs: Long?,
    val rawLimit: String?,
    val rawRemaining: String?,
    val rawReset: String?,
    /** Retry-After header as an absolute time (Unix ms), seconds or HTTP-date form. */
    val retryAfterAtMs: Long? = null,
) {
    val isEmpty: Boolean get() = rawLimit == null && rawRemaining == null && rawReset == null

    companion object {
        val EMPTY = RateLimitHeaders(null, null, null, null, null, null)

        fun parse(header: (String) -> String?): RateLimitHeaders {
            return parseAt(header, System.currentTimeMillis())
        }

        /**
         * X-RateLimit-Limit/-Remaining/-Reset (OpenRouter: reset = epoch ms) and the OpenAI/Groq-style
         * x-ratelimit-limit-requests / -remaining-requests / -reset-requests (reset = duration like "1s", "6m0s", "250ms").
         */
        fun parseAt(header: (String) -> String?, now: Long): RateLimitHeaders {
            val l = (header("X-RateLimit-Limit") ?: header("x-ratelimit-limit-requests"))?.trim()
            val r = (header("X-RateLimit-Remaining") ?: header("x-ratelimit-remaining-requests"))?.trim()
            val s = header("X-RateLimit-Reset")?.trim()
            val sReq = header("x-ratelimit-reset-requests")?.trim()
            return RateLimitHeaders(
                limit = l?.toDoubleOrNull()?.toInt(),
                remaining = r?.toDoubleOrNull()?.toInt(),
                resetAtMs = s?.let { v -> v.toDoubleOrNull()?.toLong()?.let { normaliseEpoch(it) } ?: durationMs(v)?.let { now + it } }
                    ?: sReq?.let { durationMs(it) }?.let { now + it },
                rawLimit = l, rawRemaining = r, rawReset = s ?: sReq,
                retryAfterAtMs = retryAfterAt(header("Retry-After"), now),
            )
        }

        /** Retry-After: delta seconds ("12", "1.5") or an HTTP-date; null if absent / unparsable. */
        fun retryAfterAt(v: String?, now: Long): Long? {
            val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            t.toDoubleOrNull()?.let { return if (it < 0) null else now + (it * 1000).toLong() }
            return runCatching { java.time.ZonedDateTime.parse(t, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }

        /** retry_after / retryAfter (seconds) in an error body's `error.metadata` (or top level); absolute ms or null. */
        fun retryAfterFromBody(body: String?, now: Long): Long? {
            if (body.isNullOrBlank()) return null
            val m = Regex("""["']retry[_-]?after(?:_seconds)?["']\s*:\s*"?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(body) ?: return null
            return m.groupValues[1].toDoubleOrNull()?.let { now + (it * 1000).toLong() }
        }

        /** "6m0s", "1.5s", "250ms", "1h2m" → ms; null if not a duration. */
        fun durationMs(v: String): Long? {
            val parts = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""").findAll(v.trim()).toList()
            if (parts.isEmpty() || parts.joinToString("") { it.value } != v.trim()) return null
            return parts.sumOf { val n = it.groupValues[1].toDouble(); when (it.groupValues[2]) { "ms" -> n; "s" -> n * 1e3; "m" -> n * 6e4; else -> n * 3.6e6 }.toLong() }
        }

        /** OpenRouter documents Unix ms; be tolerant of seconds. */
        fun normaliseEpoch(v: Long): Long = if (v in 1..99_999_999_999L) v * 1000 else v
    }
}

enum class ApiErrorKind { RATE_LIMIT, PAYMENT, AUTH, UPSTREAM, BAD_REQUEST, OTHER }

data class DetectedApiError(
    val kind: ApiErrorKind,
    val code: Int,
    val message: String,
    val errorType: String?,
)

object ApiErrorDetector {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Detects OpenRouter errors from the HTTP status AND the body. OpenRouter can return
     * `{"error":{"code":429,"message":...,"metadata":{"error_type":"rate_limit_exceeded"}}}`
     * with HTTP 200 (e.g. mid-stream), or put an `error` object inside a choice.
     * Returns null when the response is a normal success.
     */
    fun detect(httpCode: Int, body: String?): DetectedApiError? {
        val root = body?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        val errorObj: JsonObject? = root?.get("error")?.asObjectOrNull()
            ?: root?.get("choices")?.asArrayOrNull()?.firstNotNullOfOrNull { it.asObjectOrNull()?.get("error")?.asObjectOrNull() }

        if (errorObj == null) {
            if (httpCode in 200..299) {
                val finish = root?.get("choices")?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
                    ?.get("finish_reason")?.primitiveContent()
                return if (finish == "error") DetectedApiError(ApiErrorKind.UPSTREAM, 502, "Upstream returned finish_reason=error", null) else null
            }
            val msg = root?.get("message")?.primitiveContent() ?: body?.take(300) ?: "HTTP $httpCode"
            return DetectedApiError(kindFor(httpCode, null), httpCode, msg, null)
        }

        val code = errorObj["code"]?.primitiveContent()?.toDoubleOrNull()?.toInt()
            ?: if (httpCode >= 400) httpCode else 500
        val metadata = errorObj["metadata"]?.asObjectOrNull()
        val errorType = metadata?.get("error_type")?.primitiveContent()
            ?: errorObj["type"]?.primitiveContent()
        val raw = metadata?.get("raw")?.primitiveContent()
        val message = errorObj["message"]?.primitiveContent()?.let { if (raw != null && raw !in it) "$it ($raw)" else it }
            ?: raw ?: "Error $code"
        return DetectedApiError(kindFor(code, errorType), code, message.take(500), errorType)
    }

    fun kindFor(code: Int, errorType: String?): ApiErrorKind = when {
        code == 429 || errorType?.contains("rate_limit", ignoreCase = true) == true -> ApiErrorKind.RATE_LIMIT
        code == 402 -> ApiErrorKind.PAYMENT
        code == 401 || code == 403 -> ApiErrorKind.AUTH
        code >= 500 || code == 408 -> ApiErrorKind.UPSTREAM
        code in 400..499 -> ApiErrorKind.BAD_REQUEST
        else -> ApiErrorKind.OTHER
    }
}

data class ParsedToolCall(val id: String, val name: String, val argumentsJson: String)

data class ParsedCompletion(
    val content: String?,
    val toolCalls: List<ParsedToolCall>,
    val finishReason: String?,
    val model: String?,
    /** Model thinking, if the response carried any (see [ReasoningText]); UI-only, never sent back. */
    val reasoning: String? = null,
)

object CompletionParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Defensive parse of an OpenAI-compatible chat completion. Returns null if there is no usable choice. */
    fun parse(body: String): ParsedCompletion? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val choice = root["choices"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull() ?: return null
        val message = choice["message"]?.asObjectOrNull() ?: return null
        val rawContent = when (val c = message["content"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { part -> part.asObjectOrNull()?.get("text")?.primitiveContent() }.joinToString("")
            else -> c.toString()
        }
        val calls = message["tool_calls"]?.asArrayOrNull().orEmpty().mapIndexedNotNull { i, el ->
            val o = el.asObjectOrNull() ?: return@mapIndexedNotNull null
            val fn = o["function"]?.asObjectOrNull() ?: return@mapIndexedNotNull null
            val name = fn["name"]?.primitiveContent() ?: return@mapIndexedNotNull null
            val args = when (val a = fn["arguments"]) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> a.contentOrNull?.ifBlank { "{}" } ?: "{}"
                else -> a.toString()
            }
            ParsedToolCall(o["id"]?.primitiveContent() ?: "call_${System.currentTimeMillis()}_$i", name, args)
        }
        val split = ReasoningText.splitInline(rawContent)
        val reasoning = ReasoningText.merge(ReasoningText.fromMessage(message), split.reasoning)
        return ParsedCompletion(split.content, calls, choice["finish_reason"]?.primitiveContent(), root["model"]?.primitiveContent(), reasoning)
    }
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
internal fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
internal fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
