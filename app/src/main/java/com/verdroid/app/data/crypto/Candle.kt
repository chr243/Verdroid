package com.verdroid.app.data.crypto

import kotlinx.serialization.json.*

/** One OHLCV candle; [time] = open time in epoch seconds (UTC). */
data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

/** Coinbase Exchange candle rows `[time, low, high, open, close, volume]` (newest first) → oldest-first candles. */
object CoinbaseCandles {
    fun parse(el: JsonElement): List<Candle> {
        val arr = el as? JsonArray ?: throw IllegalStateException("candles response is not an array: ${el.toString().take(160)}")
        return arr.map { row -> val a = row.jsonArray
            Candle(a[0].jsonPrimitive.long, a[3].jsonPrimitive.double, a[2].jsonPrimitive.double, a[1].jsonPrimitive.double,
                a[4].jsonPrimitive.double, a[5].jsonPrimitive.double) }.sortedBy { it.time }
    }
}
