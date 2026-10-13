package com.verdroid.app.data.crypto.history

import com.verdroid.app.data.crypto.Candle
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Long-only, bar-close signals, fill at the next bar's open ± slippage, fee per fill. Pure (unit-tested). */
object BacktestEngine {
    data class Config(val capital: Double = 10_000.0, val feePct: Double = 0.1, val slippagePct: Double = 0.05, val positionPct: Double = 100.0)
    data class Trade(val entryTime: Long, val exitTime: Long, val entry: Double, val exit: Double, val returnPct: Double)
    data class Result(val strategy: String, val bars: Int, val trades: List<Trade>, val equity: List<Pair<Long, Double>>,
                      val totalReturnPct: Double, val cagrPct: Double, val maxDrawdownPct: Double, val sharpe: Double,
                      val winRatePct: Double, val buyHoldReturnPct: Double, val finalEquity: Double)

    /** Signals per bar: +1 enter/hold long, 0 flat, null = keep the current position. */
    fun signals(strategy: String, closes: DoubleArray, p: Map<String, Double>): Array<Int?> {
        val n = closes.size; val s = arrayOfNulls<Int>(n)
        when (strategy) {
            "buy_hold" -> for (i in 0 until n) s[i] = 1
            "sma_cross", "ema_cross" -> {
                val f = (p["fast"] ?: 10.0).toInt(); val sl = (p["slow"] ?: 30.0).toInt()
                require(f in 1 until sl) { "need 1 <= fast < slow" }
                val a = if (strategy == "sma_cross") sma(closes, f) else ema(closes, f)
                val b = if (strategy == "sma_cross") sma(closes, sl) else ema(closes, sl)
                for (i in 0 until n) if (!a[i].isNaN() && !b[i].isNaN()) s[i] = if (a[i] > b[i]) 1 else 0
            }
            "rsi" -> {
                val r = rsi(closes, (p["period"] ?: 14.0).toInt()); val lo = p["oversold"] ?: 30.0; val hi = p["overbought"] ?: 70.0
                for (i in 0 until n) if (!r[i].isNaN()) s[i] = when { r[i] < lo -> 1; r[i] > hi -> 0; else -> null }
            }
            "bollinger" -> {
                val per = (p["period"] ?: 20.0).toInt(); val k = p["stddev"] ?: 2.0
                val m = sma(closes, per)
                for (i in per - 1 until n) {
                    val sd = sqrt((i - per + 1..i).sumOf { (closes[it] - m[i]).pow(2) } / per)
                    s[i] = when { closes[i] < m[i] - k * sd -> 1; closes[i] >= m[i] -> 0; else -> null } // buy below lower band, exit at the mean
                }
            }
            else -> throw IllegalArgumentException("unknown strategy '$strategy' (sma_cross, ema_cross, rsi, bollinger, buy_hold, custom)")
        }
        return s
    }

    fun run(candles: List<Candle>, strategy: String, params: Map<String, Double>, cfg: Config, barSeconds: Long): Result {
        require(candles.size >= 2) { "need at least 2 candles" }
        val closes = DoubleArray(candles.size) { candles[it].close }
        val sig = signals(strategy, closes, params)
        val fee = cfg.feePct / 100; val slip = cfg.slippagePct / 100; val frac = (cfg.positionPct / 100).coerceIn(0.0, 1.0)
        var cash = cfg.capital; var qty = 0.0; var entryPx = 0.0; var entryT = 0L; var want = 0
        val trades = mutableListOf<Trade>(); val eq = ArrayList<Pair<Long, Double>>(candles.size)
        for (i in candles.indices) {
            val c = candles[i]
            // execute yesterday's decision at this bar's open
            if (want == 1 && qty == 0.0) {
                val px = c.open * (1 + slip); val spend = cash * frac
                qty = spend * (1 - fee) / px; cash -= spend; entryPx = px; entryT = c.time
            } else if (want == 0 && qty > 0) {
                val px = c.open * (1 - slip); cash += qty * px * (1 - fee)
                trades += Trade(entryT, c.time, entryPx, px, ((px * (1 - fee)) / (entryPx / (1 - fee)) - 1) * 100); qty = 0.0
            }
            sig[i]?.let { want = it }
            eq += c.time to cash + qty * c.close
        }
        if (qty > 0) { val c = candles.last(); val px = c.close * (1 - slip); cash += qty * px * (1 - fee)
            trades += Trade(entryT, c.time, entryPx, px, ((px * (1 - fee)) / (entryPx / (1 - fee)) - 1) * 100); qty = 0.0
            eq[eq.lastIndex] = c.time to cash }
        val m = metrics(eq.map { it.second }, barSeconds)
        val bh = (candles.last().close / candles.first().open - 1) * 100
        return Result(strategy, candles.size, trades, eq, (eq.last().second / cfg.capital - 1) * 100, m.first, m.second, m.third,
            if (trades.isEmpty()) 0.0 else trades.count { it.returnPct > 0 } * 100.0 / trades.size, bh, eq.last().second)
    }

    /** (CAGR %, max drawdown %, annualised Sharpe of per-bar returns, rf = 0). */
    fun metrics(equity: List<Double>, barSeconds: Long): Triple<Double, Double, Double> {
        var peak = equity.first(); var dd = 0.0
        for (e in equity) { peak = max(peak, e); dd = max(dd, (peak - e) / peak) }
        val years = (equity.size - 1) * barSeconds / (365.25 * 86400)
        val cagr = if (years <= 0 || equity.first() <= 0) 0.0 else ((equity.last() / equity.first()).pow(1 / years) - 1) * 100
        val r = equity.zipWithNext { a, b -> if (a == 0.0) 0.0 else b / a - 1 }
        val mean = r.average(); val sd = sqrt(r.sumOf { (it - mean).pow(2) } / max(1, r.size - 1))
        val sharpe = if (sd == 0.0 || r.isEmpty()) 0.0 else mean / sd * sqrt(365.25 * 86400 / barSeconds)
        return Triple(cagr, dd * 100, sharpe)
    }

    fun sma(x: DoubleArray, n: Int) = DoubleArray(x.size) { i -> if (i < n - 1) Double.NaN else (i - n + 1..i).sumOf { x[it] } / n }
    fun ema(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }; if (x.size < n) return out
        val k = 2.0 / (n + 1); out[n - 1] = (0 until n).sumOf { x[it] } / n
        for (i in n until x.size) out[i] = x[i] * k + out[i - 1] * (1 - k)
        return out
    }
    /** Wilder RSI. */
    fun rsi(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }; if (x.size <= n) return out
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = x[i] - x[i - 1]; if (d > 0) g += d else l -= d }
        g /= n; l /= n; out[n] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
        for (i in n + 1 until x.size) { val d = x[i] - x[i - 1]
            g = (g * (n - 1) + max(d, 0.0)) / n; l = (l * (n - 1) + max(-d, 0.0)) / n
            out[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l) }
        return out
    }
}
