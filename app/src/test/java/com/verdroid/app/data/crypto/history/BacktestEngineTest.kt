package com.verdroid.app.data.crypto.history

import com.verdroid.app.data.crypto.Candle
import org.junit.Assert.*
import org.junit.Test

class BacktestEngineTest {
    private fun series(vararg p: Double) = p.mapIndexed { i, x -> Candle(i * 86400L, x, x, x, x, 1.0) }
    private val noCost = BacktestEngine.Config(1000.0, 0.0, 0.0, 100.0)

    @Test fun indicators() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(2.0, BacktestEngine.sma(x, 3)[2], 1e-9); assertTrue(BacktestEngine.sma(x, 3)[1].isNaN())
        val e = BacktestEngine.ema(x, 3); assertEquals(2.0, e[2], 1e-9); assertEquals(3.0, e[3], 1e-9)
        assertEquals(100.0, BacktestEngine.rsi(x, 3)[3], 1e-9) // only gains
    }

    @Test fun buyHoldMatchesPriceAndFeesCost() {
        val c = series(100.0, 110.0, 120.0, 150.0)
        val r = BacktestEngine.run(c, "buy_hold", emptyMap(), noCost, 86400)
        assertEquals(36.36, r.totalReturnPct, 0.01) // bought at bar 1 open (110) → 150
        assertEquals(50.0, r.buyHoldReturnPct, 1e-9)
        val withFee = BacktestEngine.run(c, "buy_hold", emptyMap(), noCost.copy(feePct = 1.0), 86400)
        assertTrue(withFee.totalReturnPct < r.totalReturnPct)
        assertEquals(1, r.trades.size); assertEquals(100.0, r.winRatePct, 0.0)
    }

    @Test fun noLookahead() {
        // Signal on the last bar can't trade: one-bar series up then flat → sma cross enters next bar only.
        val c = series(*DoubleArray(40) { if (it < 20) 100.0 - it else 80.0 + (it - 20) * 3 })
        val r = BacktestEngine.run(c, "sma_cross", mapOf("fast" to 3.0, "slow" to 10.0), noCost, 86400)
        assertTrue(r.trades.isNotEmpty())
        r.trades.forEach { t -> assertTrue(t.entryTime > 0) }
        assertTrue(r.totalReturnPct > 0)
    }

    @Test fun metrics() {
        val (cagr, dd, sharpe) = BacktestEngine.metrics(listOf(100.0, 120.0, 90.0, 110.0), 86400)
        assertEquals(25.0, dd, 1e-9); assertTrue(cagr > 0)
        assertEquals(0.0, BacktestEngine.metrics(listOf(1.0, 1.0, 1.0), 86400).third, 0.0)
        assertTrue(sharpe.isFinite())
    }

    @Test fun unknownStrategy() {
        assertThrows(IllegalArgumentException::class.java) { BacktestEngine.run(series(1.0, 2.0), "magic", emptyMap(), noCost, 86400) }
    }
}
