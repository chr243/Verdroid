package com.verdroid.app.data.crypto.history

import com.verdroid.app.data.crypto.Candle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarketHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun c(t: Long, p: Double = 100.0) = Candle(t, p, p + 1, p - 1, p, 1.0)
    private val btc = Symbol.parse("BTC-USDT")

    @Test fun symbolsAndIntervals() {
        assertEquals("BTC-USDT", Symbol.parse("btcusdt").id)
        assertEquals("ETH-EUR", Symbol.parse("ETH/EUR").id)
        assertEquals("BTCUSDT", btc.binance); assertEquals("BTC-USD", btc.coinbase); assertEquals("XBTUSD", btc.kraken)
        assertEquals(Interval.H1, Interval.parse("1h")); assertEquals(Interval.D1, Interval.parse("86400")); assertNull(Interval.parse("7m"))
        assertEquals(1704067200L, HistoryTime.parse("2024-01-01", 0)); assertEquals(1704067200L, HistoryTime.parse("1704067200000", 0))
    }

    @Test fun mergeDedupesAndNewerWins() {
        val m = CandleCache.merge(listOf(c(0), c(60, 1.0)), listOf(c(60, 2.0), c(120)))
        assertEquals(listOf(0L, 60L, 120L), m.map { it.time }); assertEquals(2.0, m[1].close, 0.0)
    }

    @Test fun missingRangesAndGaps() {
        val i = Interval.M1
        assertEquals(listOf(0L until 600L), CandleCache.missing(emptyList(), i, 0, 600))
        val cached = listOf(c(120), c(180), c(300))
        assertEquals(listOf(0L until 120L, 360L until 600L), CandleCache.missing(cached, i, 0, 600))
        assertTrue(CandleCache.missing(cached, i, 120, 360).isEmpty())
        assertEquals(listOf(240L to 300L), CandleCache.gaps(cached, i))
    }

    @Test fun binancePaginates() = runBlocking {
        val urls = mutableListOf<String>()
        val src = BinanceHistory({ url ->
            urls += url
            val start = Regex("startTime=(\\d+)").find(url)!!.groupValues[1].toLong() / 1000
            val n = if (start == 0L) BinanceHistory.PAGE else 5
            "[" + (0 until n).joinToString(",") { k -> val t = (start + k * 60) * 1000; "[$t,\"1\",\"2\",\"0.5\",\"1.5\",\"3\",${t + 59999},\"0\",1,\"0\",\"0\",\"0\"]" } + "]"
        })
        val got = src.fetch(btc, Interval.M1, 0, 2000L * 60)
        assertEquals(2, urls.size); assertEquals(BinanceHistory.PAGE + 5, got.size)
        assertEquals(BinanceHistory.PAGE * 60L, got[BinanceHistory.PAGE].time)
    }

    @Test fun coinbaseWindowsOf300() = runBlocking {
        var calls = 0
        val src = CoinbaseHistory({ url -> calls++; assertTrue(url.contains("/products/BTC-USD/candles?granularity=3600")); "[]" })
        src.fetch(btc, Interval.H1, 0, 700L * 3600)
        assertEquals(3, calls)
    }

    @Test fun krakenParse() {
        val body = """{"error":[],"result":{"XXBTZUSD":[[1700000000,"1","2","0.5","1.5","1.2","9.0",3]],"last":1700000000}}"""
        val k = KrakenHistory.parse(body).single(); assertEquals(1.5, k.close, 0.0); assertEquals(9.0, k.volume, 0.0)
    }

    @Test fun downloaderFallsBackAndIsIncremental() = runBlocking {
        val bad = object : HistorySource { override val name = "binance"
            override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle> = throw java.io.IOException("451") }
        var fetched = 0
        val good = object : HistorySource { override val name = "coinbase"
            override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long): List<Candle> {
                fetched++; return (startSec until endSec step 86400).map { c(it) } } }
        val d = HistoryDownloader(CandleCache(tmp.root), listOf(bad, good))
        val r1 = d.download(btc, Interval.D1, 0, 10 * 86400L)
        assertEquals(10, r1.added); assertEquals(listOf("coinbase"), r1.sources); assertTrue(r1.errors.first().startsWith("binance"))
        val r2 = d.download(btc, Interval.D1, 0, 10 * 86400L)
        assertEquals(0, r2.added); assertEquals(1, fetched)
        val r3 = d.download(btc, Interval.D1, 0, 12 * 86400L)
        assertEquals(2, r3.added); assertEquals(12, d.cache.load(btc, Interval.D1).size)
    }
}
