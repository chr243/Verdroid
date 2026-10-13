package com.verdroid.app.data.crypto.history

import com.verdroid.app.data.crypto.Candle
import kotlinx.serialization.json.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Candle intervals supported by the history cache (seconds + each source's code; null = source can't serve it). */
enum class Interval(val code: String, val seconds: Long, val binance: String, val coinbase: Int?, val krakenMin: Int?) {
    M1("1m", 60, "1m", 60, 1), M3("3m", 180, "3m", null, null), M5("5m", 300, "5m", 300, 5),
    M15("15m", 900, "15m", 900, 15), M30("30m", 1800, "30m", null, 30), H1("1h", 3600, "1h", 3600, 60),
    H2("2h", 7200, "2h", null, null), H4("4h", 14400, "4h", null, 240), H6("6h", 21600, "6h", 21600, null),
    H12("12h", 43200, "12h", null, null), D1("1d", 86400, "1d", 86400, 1440);

    companion object {
        fun parse(s: String?): Interval? {
            val v = s?.trim()?.lowercase() ?: return null
            entries.firstOrNull { it.code == v }?.let { return it }
            v.toLongOrNull()?.let { sec -> return entries.firstOrNull { it.seconds == sec } } // granularity_seconds
            return null
        }
        val CODES get() = entries.joinToString(", ") { it.code }
    }
}

/** Canonical symbol "BTC-USDT" and its spellings per source. */
data class Symbol(val base: String, val quote: String) {
    val id get() = "$base-$quote"
    val binance get() = base + quote
    /** Coinbase has USD (not USDT) for most coins. */
    val coinbase get() = "$base-${if (quote == "USDT") "USD" else quote}"
    val kraken get() = (if (base == "BTC") "XBT" else base) + (if (quote == "USDT") "USD" else quote)

    companion object {
        private val QUOTES = listOf("USDT", "USDC", "FDUSD", "BUSD", "USD", "EUR", "GBP", "BTC", "ETH", "BNB", "TRY", "JPY")
        fun parse(raw: String?): Symbol {
            val s = raw?.trim()?.uppercase()?.replace('/', '-')?.replace('_', '-').orEmpty()
            if ('-' in s) { val (b, q) = s.split('-', limit = 2); require(valid(b) && valid(q)) { "bad symbol '$raw'" }; return Symbol(b, q) }
            val q = QUOTES.firstOrNull { s.endsWith(it) && s.length > it.length } ?: throw IllegalArgumentException("symbol must look like BTC-USDT (got '$raw')")
            return Symbol(s.removeSuffix(q), q)
        }
        private fun valid(x: String) = x.matches(Regex("[A-Z0-9]{2,10}"))
    }
}

/** Dates: "2024-01-31", epoch seconds or epoch ms → epoch seconds (UTC). */
object HistoryTime {
    fun parse(s: String?, default: Long): Long {
        val v = s?.trim().orEmpty()
        if (v.isEmpty() || v == "now") return default
        v.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000 else it }
        return LocalDate.parse(v.take(10)).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }
    fun iso(sec: Long): String = java.time.Instant.ofEpochSecond(sec).toString()
    fun day(sec: Long): String = iso(sec).take(10)
}

/** A keyless OHLCV source. [fetch] returns candles with open time in [startSec, endSec), oldest first. */
interface HistorySource {
    val name: String
    suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle>
}

typealias HttpGet = suspend (String) -> String

/** Binance public market data (data-api.binance.vision works where api.binance.com is geo-blocked). 1000 per page. */
class BinanceHistory(private val get: HttpGet, private val base: String = "https://data-api.binance.vision") : HistorySource {
    override val name = "binance"
    override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle> {
        val out = ArrayList<Candle>(); var from = startSec
        while (from < endSec) {
            val body = get("$base/api/v3/klines?symbol=${symbol.binance}&interval=${interval.binance}&startTime=${from * 1000}&endTime=${endSec * 1000 - 1}&limit=$PAGE")
            val page = parse(body)
            if (page.isEmpty()) break
            out += page
            val next = page.last().time + interval.seconds
            if (next <= from) break
            from = next
            if (page.size < PAGE) break
        }
        return out.filter { it.time in startSec until endSec }
    }
    companion object {
        const val PAGE = 1000
        fun parse(body: String): List<Candle> {
            val el = Json.parseToJsonElement(body)
            if (el is JsonObject) throw IllegalStateException("binance: ${el["msg"]?.jsonPrimitive?.contentOrNull ?: el}")
            return el.jsonArray.map { r -> val a = r.jsonArray
                Candle(a[0].jsonPrimitive.long / 1000, a[1].d(), a[2].d(), a[3].d(), a[4].d(), a[5].d()) }
        }
    }
}

/** Coinbase Exchange candles: 300 per request, windows walked oldest → newest. */
class CoinbaseHistory(private val get: HttpGet, private val base: String = "https://api.exchange.coinbase.com") : HistorySource {
    override val name = "coinbase"
    override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle> {
        val g = interval.coinbase ?: throw UnsupportedOperationException("coinbase has no ${interval.code} candles")
        val out = ArrayList<Candle>(); var from = startSec
        while (from < endSec) {
            val to = minOf(endSec, from + g * PAGE)
            val body = get("$base/products/${symbol.coinbase}/candles?granularity=$g&start=${HistoryTime.iso(from)}&end=${HistoryTime.iso(to - 1)}")
            out += com.verdroid.app.data.crypto.CoinbaseExchangeClient.parseCandles(Json.parseToJsonElement(body))
            from = to
        }
        return out.filter { it.time in startSec until endSec }.distinctBy { it.time }.sortedBy { it.time }
    }
    companion object { const val PAGE = 300L }
}

/** Kraken OHLC: only the most recent 720 candles per interval (last-resort fallback). */
class KrakenHistory(private val get: HttpGet, private val base: String = "https://api.kraken.com") : HistorySource {
    override val name = "kraken"
    override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle> {
        val m = interval.krakenMin ?: throw UnsupportedOperationException("kraken has no ${interval.code} candles")
        return parse(get("$base/0/public/OHLC?pair=${symbol.kraken}&interval=$m&since=${startSec - 1}")).filter { it.time in startSec until endSec }
    }
    companion object {
        fun parse(body: String): List<Candle> {
            val o = Json.parseToJsonElement(body).jsonObject
            val err = o["error"]?.jsonArray.orEmpty()
            if (err.isNotEmpty()) throw IllegalStateException("kraken: ${err.joinToString { it.jsonPrimitive.content }}")
            val res = o["result"]!!.jsonObject
            val rows = res.entries.first { it.key != "last" }.value.jsonArray
            return rows.map { r -> val a = r.jsonArray; Candle(a[0].jsonPrimitive.long, a[1].d(), a[2].d(), a[3].d(), a[4].d(), a[6].d()) }
        }
    }
}

private fun JsonElement.d() = jsonPrimitive.content.toDouble()

/** CSV cache, one file per symbol+interval: `time,open,high,low,close,volume` (time = open, epoch seconds UTC). */
class CandleCache(val dir: File) {
    fun file(s: Symbol, i: Interval) = File(dir, "${s.id}_${i.code}.csv")

    fun load(s: Symbol, i: Interval): List<Candle> {
        val f = file(s, i); if (!f.isFile) return emptyList()
        return f.readLines().drop(1).mapNotNull { l -> val p = l.split(','); if (p.size < 6) null else runCatching {
            Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble()) }.getOrNull() }
    }

    fun save(s: Symbol, i: Interval, candles: List<Candle>) {
        dir.mkdirs()
        val tmp = File(dir, file(s, i).name + ".tmp")
        tmp.bufferedWriter().use { w -> w.write(HEADER); w.newLine()
            candles.forEach { c -> w.write("${c.time},${c.open},${c.high},${c.low},${c.close},${c.volume}"); w.newLine() } }
        if (!tmp.renameTo(file(s, i))) { tmp.copyTo(file(s, i), overwrite = true); tmp.delete() }
    }

    fun list(): List<Pair<String, File>> = dir.listFiles().orEmpty().filter { it.name.endsWith(".csv") }.sortedBy { it.name }.map { it.nameWithoutExtension to it }

    companion object {
        const val HEADER = "time,open,high,low,close,volume"
        /** Union by open time; newer data wins for duplicates; sorted. */
        fun merge(old: List<Candle>, new: List<Candle>): List<Candle> =
            (old.associateBy { it.time } + new.associateBy { it.time }).values.sortedBy { it.time }

        /** Ranges still missing inside [from, to) given cached candles (only before the first / after the last). */
        fun missing(cached: List<Candle>, i: Interval, from: Long, to: Long): List<LongRange> {
            val a = align(from, i); val b = align(to, i)
            if (a >= b) return emptyList()
            if (cached.isEmpty()) return listOf(a until b)
            val out = mutableListOf<LongRange>()
            val first = cached.first().time; val last = cached.last().time + i.seconds
            if (a < first) out += a until minOf(first, b)
            if (b > last) out += maxOf(last, a) until b
            return out.filter { it.last >= it.first }
        }

        /** Internal holes: pairs (gapStart, gapEnd) where consecutive candles are more than one interval apart. */
        fun gaps(c: List<Candle>, i: Interval): List<Pair<Long, Long>> =
            c.zipWithNext().filter { (x, y) -> y.time - x.time > i.seconds }.map { (x, y) -> (x.time + i.seconds) to y.time }

        fun align(t: Long, i: Interval) = t - Math.floorMod(t, i.seconds)
    }
}

data class DownloadResult(val symbol: String, val interval: String, val added: Int, val total: Int, val first: Long?, val last: Long?,
                          val sources: List<String>, val gaps: List<Pair<Long, Long>>, val errors: List<String>, val file: File)

/** Incremental downloader: fetch only missing ranges, sources in order (fallback on error/empty), merge + dedupe. */
class HistoryDownloader(val cache: CandleCache, private val sources: List<HistorySource>) {
    suspend fun download(symbol: Symbol, interval: Interval, from: Long, to: Long): DownloadResult {
        val cached = cache.load(symbol, interval)
        var merged = cached; val used = mutableListOf<String>(); val errors = mutableListOf<String>()
        for (r in CandleCache.missing(cached, interval, from, to)) {
            for (src in sources) {
                val got = runCatching { src.fetch(symbol, interval, r.first, r.last + 1) }
                    .onFailure { errors += "${src.name}: ${it.message?.take(160)}" }.getOrNull() ?: continue
                if (got.isEmpty()) { errors += "${src.name}: no data for ${HistoryTime.day(r.first)}..${HistoryTime.day(r.last)}"; continue }
                merged = CandleCache.merge(merged, got); used += src.name; break
            }
        }
        if (merged.size != cached.size) cache.save(symbol, interval, merged)
        val inRange = merged.filter { it.time in from until to }
        return DownloadResult(symbol.id, interval.code, merged.size - cached.size, merged.size, merged.firstOrNull()?.time, merged.lastOrNull()?.time,
            used.distinct(), CandleCache.gaps(inRange, interval), errors, cache.file(symbol, interval))
    }
}
