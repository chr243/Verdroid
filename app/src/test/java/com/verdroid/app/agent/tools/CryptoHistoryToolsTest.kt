package com.verdroid.app.agent.tools

import com.verdroid.app.data.crypto.Candle
import com.verdroid.app.data.crypto.history.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CryptoHistoryToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun tools(): List<AgentTool> {
        val src = object : HistorySource { override val name = "fake"
            override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long) =
                (startSec until endSec step interval.seconds).map { t -> val i = t / 86400; val p = if (i < 40) 140.0 - i else 100.0 + (i - 40) * 1.5
                    Candle(t, p, p + 1, p - 1, p, 1.0) } }
        val d = HistoryDownloader(CandleCache(tmp.newFolder("md")), listOf(src))
        return CryptoHistoryTools({ d }, { tmp.root }, now = { 90 * 86400L }).tools()
    }

    @Test fun backtestRunsOfflineOnCache() = runBlocking {
        val t = tools()
        val r = obj(t.first { it.name == "crypto_backtest" }.execute(buildJsonObject {
            put("pair", "BTC-USD"); put("granularity_seconds", 86400); put("from", "0"); put("fast", 5); put("slow", 20); put("fee_pct", 0.0); put("save_csv", true) }))
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r["total_return_pct"]!!.jsonPrimitive.double > 0)
        assertEquals(CryptoHistoryTools.DISCLAIMER, r["disclaimer"]!!.jsonPrimitive.content)
        assertTrue(r["reply_instruction"]!!.jsonPrimitive.content.contains("End your reply"))
        assertEquals("disclaimer", r.keys.last())
        listOf("cagr_pct", "max_drawdown_pct", "sharpe", "win_rate_pct", "buy_hold_return_pct").forEach { assertTrue(it, r.containsKey(it)) }
        assertTrue(java.io.File(r["equity_csv"]!!.jsonPrimitive.content).isFile)
        val l = obj(t.first { it.name == "crypto_history_list" }.execute(JsonObject(emptyMap())))
        assertEquals("BTC-USD", l["files"]!!.jsonArray.single().jsonObject["symbol"]!!.jsonPrimitive.content)
        val custom = obj(t.first { it.name == "crypto_backtest" }.execute(buildJsonObject { put("symbol", "BTC-USD"); put("from", "0"); put("strategy", "custom") }))
        assertTrue(custom["python_template"]!!.jsonPrimitive.content.contains("read_csv"))
    }

    @Test fun scriptToolWithoutTermuxFailsCleanly() = runBlocking {
        val r = obj(tools().first { it.name == "crypto_backtest_script" }.execute(buildJsonObject { put("strategy", "x") }))
        assertTrue(r.containsKey("error"))
    }

    @Test fun harnessAndHelpers() {
        assertTrue(CryptoHistoryTools.harnessSource().contains("def generate_signals") || CryptoHistoryTools.harnessSource().contains("generate_signals("))
        assertEquals("rsi_dip", CryptoHistoryTools.strategyName("strategies/rsi_dip.py")); assertEquals("evil", CryptoHistoryTools.strategyName("../evil"))
        assertThrows(IllegalArgumentException::class.java) { CryptoHistoryTools.strategyName("bad name!") }
        val cmd = CryptoHistoryTools.scriptCommand("/h.py", "/s it.py", "/d.csv", "/o", "s", "BTC-USDT", Interval.H1, 0, 3600,
            buildJsonObject { put("fast", 5) }, 1000.0, 0.1, 0.05, 100.0, false, true, 60)
        assertTrue(cmd.contains("'/s it.py'")); assertTrue(cmd.contains("""'{"fast":5}'""")); assertTrue(cmd.contains("--bar_seconds' '3600'"))
        val ok = CryptoHistoryTools.parseHarnessOutput("log line\n${CryptoHistoryTools.MARK}{\"ok\":true,\"total_return_pct\":12.5}", "", 0, "Internal/strategies/s.py", null)
        assertEquals(12.5, ok["total_return_pct"]!!.jsonPrimitive.double, 0.0)
        assertEquals(CryptoHistoryTools.DISCLAIMER, ok["disclaimer"]!!.jsonPrimitive.content); assertEquals("disclaimer", ok.keys.last())
        assertTrue(CryptoHistoryTools.DISCLAIMER.startsWith("⚠️ Not financial advice.")); assertEquals("log line", ok["strategy_output"]!!.jsonPrimitive.content)
        val miss = CryptoHistoryTools.parseHarnessOutput("${CryptoHistoryTools.MARK}{\"ok\":false,\"missing_packages\":true,\"error\":\"x\"}", "", 0, "s", null)
        assertTrue(miss.containsKey("hint") && miss.containsKey("error"))
        assertTrue(CryptoHistoryTools.parseHarnessOutput("", "boom", 1, "s", null).containsKey("error"))
    }
}
