package com.verdroid.app.agent.tools

import com.verdroid.app.data.crypto.history.*
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.data.termux.TermuxResult
import com.verdroid.app.data.termux.TermuxRunner
import com.verdroid.app.data.tools.TermuxPackages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BacktestPreflightTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTermux(var deps: Boolean) : TermuxRunner {
        val commands = mutableListOf<String>()
        override fun isInstalled() = true
        override fun hasRunCommandPermission() = true
        override suspend fun runAndWait(command: String, tag: String, timeoutMs: Long, label: String): TermuxResult {
            commands += command
            val out = if (command == CryptoHistoryTools.PREFLIGHT) (if (deps) "DEPS=1" else "DEPS=0")
                else "${CryptoHistoryTools.MARK}{\"ok\":true,\"total_return_pct\":1.0}"
            return TermuxResult(tag, out, "", 0, null, null)
        }
    }

    private fun tool(t: FakeTermux): AgentTool {
        val folder = SharedFolder(tmp.newFolder("Verdroid")) { true }
        val src = object : HistorySource { override val name = "fake"
            override suspend fun fetch(symbol: Symbol, interval: Interval, startSec: Long, endSec: Long) =
                (startSec until endSec step interval.seconds).map { com.verdroid.app.data.crypto.Candle(it, 1.0, 1.0, 1.0, 1.0, 1.0) } }
        val d = HistoryDownloader(CandleCache(tmp.newFolder("md")), listOf(src))
        return CryptoHistoryTools({ d }, now = { 30 * 86400L }, termux = t, folder = folder).tools().first { it.name == "crypto_backtest_script" }
    }

    private val args = buildJsonObject { put("strategy", "s"); put("code", "def generate_signals(df, p):\n    return df.close*0+1\n"); put("from", "0") }

    @Test fun missingDepsTellsUserToInstallInSettings() = runBlocking {
        CryptoHistoryTools.resetPreflightCache()
        val t = FakeTermux(deps = false)
        val r = Json.parseToJsonElement(tool(t).execute(args)).jsonObject
        assertEquals(true, r["needs_setup"]!!.jsonPrimitive.boolean)
        assertTrue(r["tell_user"]!!.jsonPrimitive.content.contains("Settings → Tools"))
        assertFalse(r.containsKey("meanwhile"))
        assertTrue(r["next_step"]!!.jsonPrimitive.content.contains("STOP"))
        assertTrue(r["next_step"]!!.jsonPrimitive.content.contains("unless the user explicitly asks"))
        assertEquals(1, t.commands.size) // only the preflight, no install, no run
        assertFalse(t.commands.any { "pip install" in it || "apt" in it })
    }

    @Test fun positiveCheckIsCached() = runBlocking {
        CryptoHistoryTools.resetPreflightCache()
        val t = FakeTermux(deps = true)
        val tl = tool(t)
        val r1 = Json.parseToJsonElement(tl.execute(args)).jsonObject
        assertEquals(true, r1["ok"]!!.jsonPrimitive.boolean)
        tl.execute(args)
        assertEquals(1, t.commands.count { it == CryptoHistoryTools.PREFLIGHT })
        CryptoHistoryTools.resetPreflightCache()
    }

    @Test fun packageEntry() {
        val p = TermuxPackages.CRYPTO_BACKTEST
        assertTrue(p in TermuxPackages.ALL)
        assertTrue(p.detect.contains("import pandas, numpy, matplotlib"))
        val i = p.install!!
        assertTrue(i.indexOf("python-numpy") < i.indexOf("pip install numpy")) // prebuilt first
        assertTrue(i.contains("python-pandas") && i.contains("matplotlib"))
        assertTrue(CryptoHistoryTools.preflightOk("x\nDEPS=1")); assertFalse(CryptoHistoryTools.preflightOk("DEPS=0")); assertFalse(CryptoHistoryTools.preflightOk(null))
    }
}
