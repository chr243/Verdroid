package com.verdroid.app.agent.tools

import com.verdroid.app.data.crypto.history.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.round

/**
 * Keyless crypto history + offline backtests: crypto_history_download / crypto_history_list fill a CSV cache
 * (Documents/Verdroid/Internal/market-data), crypto_backtest runs on that cache (auto-downloads missing ranges).
 */
class CryptoHistoryTools(
    private val downloader: () -> HistoryDownloader,
    /** Output/ for equity CSVs (null = no shared folder access). */
    private val outputDir: () -> File? = { null },
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** For crypto_backtest_script: Termux + the shared folder (Internal/strategies, Internal/backtest, Output/backtests). */
    private val termux: com.verdroid.app.data.termux.TermuxRunner? = null,
    private val folder: com.verdroid.app.data.storage.SharedFolder? = null,
) {
    fun tools(): List<AgentTool> = listOf(download(), list(), backtest(), backtestScript())

    /**
     * Main flow: the agent writes Internal/strategies/<name>.py with generate_signals(df, params) and runs it through the
     * shipped harness (no lookahead, fees/slippage/sizing, metrics, equity CSV/PNG in Output/backtests).
     */
    private fun backtestScript() = object : AgentTool {
        override val name = "crypto_backtest_script"
        override val description = "Backtest a Python strategy file on cached candles (main way to test strategies). The strategy lives in " +
            "Documents/Verdroid/Internal/strategies/<strategy>.py and must define generate_signals(df, params) -> pandas Series of target " +
            "position per bar (0..1, or -1..1 with allow_short); optional PARAMS dict of defaults. df columns: time, open, high, low, close, " +
            "volume (DatetimeIndex, UTC). Pass code to create/overwrite the file. The harness shifts signals one bar (no lookahead), applies " +
            "fees, slippage and position sizing, and returns total return, CAGR, max drawdown, Sharpe, win rate, trades, exposure, buy & " +
            "hold, plus equity CSV/PNG in Output/backtests. Missing candles are downloaded first (no API key). Needs Termux + pandas."
        override val parameters = schema(listOf("strategy"),
            "strategy" to prop("string", "Strategy name (file Internal/strategies/<name>.py), letters/digits/_-"),
            "code" to prop("string", "Optional Python source to save as the strategy before running"),
            symbolProp, intervalProp, fromProp, toProp,
            "params" to buildJsonObject { put("type", "object"); put("description", "Strategy parameters passed to generate_signals (override PARAMS)") },
            "capital" to prop("number", "Initial capital (default 10000)"),
            "fee_percent" to prop("number", "Fee per trade in % of turnover (default 0.1)"),
            "slippage_percent" to prop("number", "Slippage in % of turnover (default 0.05)"),
            "position_percent" to prop("number", "Position size in % of equity at signal 1.0 (default 100)"),
            "allow_short" to prop("boolean", "Allow negative positions (default false)"),
            "plot" to prop("boolean", "Write the equity PNG (needs matplotlib; default true)"),
            "timeout_seconds" to prop("integer", "Timeout (default 300, max 600)"),
            InstallConsent.CONFIRM_PROP, InstallConsent.ID_PROP)
        override suspend fun execute(args: JsonObject): String = try {
            val t = termux; val f = folder
            if (t == null || f == null) throw IllegalStateException("crypto_backtest_script is not available here")
            if (!f.hasAccess() || !f.ensure()) throw IllegalStateException("Needs All files access for Documents/Verdroid (Settings > Permissions).")
            if (!t.isInstalled()) throw IllegalStateException("Termux is not installed. Set it up in Settings > Tools.")
            if (!t.hasRunCommandPermission()) throw IllegalStateException("Verdroid doesn't have the 'Run commands in Termux' permission (Settings > Tools).")
            if (!depsReady(t)) throw NeedsSetup()
            val name = strategyName(args.s("strategy"))
            val file = File(f.internal, "strategies/$name.py")
            args.s("code")?.let { file.parentFile?.mkdirs(); file.writeText(it) }
            if (!file.isFile) throw IllegalStateException("Strategy not found: Internal/strategies/$name.py (pass code to create it)")
            // Same install consent as termux_python: a strategy that pip-installs needs the user's OK first.
            TermuxRunTool.gate(tool = "crypto_backtest_script", reason = "the strategy $name.py installs them",
                packages = InstallConsent.detectPython(file.readText()), args = args)?.let { return it }
            val harness = File(f.internal, HARNESS_REL).also { it.parentFile?.mkdirs(); it.writeText(harnessSource()) }
            val sym = Symbol.parse(args.s("symbol") ?: "BTC-USDT"); val i = interval(args); val (from, to) = range(args, i)
            val dl = downloader().download(sym, i, from, to)
            if (dl.total == 0) throw IllegalStateException("no candles for ${sym.id} ${i.code}: ${dl.errors.joinToString("; ")}")
            val timeout = ((args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 300).coerceIn(10, TermuxRunTool.MAX_TIMEOUT_S)
            val cmd = scriptCommand(
                harness = termuxPath(f, harness), strategy = termuxPath(f, file), csv = termuxPath(f, dl.file),
                outDir = "${com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH}/Output/backtests", name = name, symbol = sym.id, interval = i,
                from = from, to = to, params = (args["params"] as? JsonObject) ?: JsonObject(emptyMap()),
                capital = args.n("capital") ?: 10_000.0, fee = args.n("fee_percent") ?: 0.1, slip = args.n("slippage_percent") ?: 0.05,
                position = args.n("position_percent") ?: 100.0, allowShort = args.b("allow_short") ?: false, plot = args.b("plot") ?: true, timeoutS = timeout)
            val r = t.runAndWait(cmd, "bt-${System.nanoTime()}", (timeout + 30) * 1000L, label = "Verdroid: backtest $name")
                ?: throw IllegalStateException("Termux did not answer within ${timeout + 30} s")
            parseHarnessOutput(r.stdout, r.stderr, r.exitCode ?: -1, "Internal/strategies/$name.py", dl).toString()
        } catch (e: NeedsSetup) { NEEDS_SETUP.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_backtest_script failed") }
    }

    private val symbolProp = "symbol" to prop("string", "Pair like BTC-USDT, ETH-EUR (BTCUSDT and BTC/USDT also work)")
    private val intervalProp = "interval" to prop("string", "Candle interval: ${Interval.CODES} (default 1d)")
    private val fromProp = "from" to prop("string", "Start date YYYY-MM-DD (UTC) or epoch; default 365 days ago")
    private val toProp = "to" to prop("string", "End date YYYY-MM-DD (exclusive) or epoch; default now")

    private fun range(args: JsonObject, i: Interval): Pair<Long, Long> {
        val to = HistoryTime.parse(args.s("to"), now())
        val from = HistoryTime.parse(args.s("from"), to - 365L * 86400)
        require(from < to) { "from must be before to" }
        require((to - from) / i.seconds <= MAX_BARS) { "range too large: max $MAX_BARS candles per request (use a bigger interval or a shorter range)" }
        return from to to
    }

    private fun download() = object : AgentTool {
        override val name = "crypto_history_download"
        override val description = "Download historical OHLCV candles (no API key) into the local cache for offline backtests. " +
            "Sources in order: Binance public data, Coinbase Exchange, Kraken (recent only). Only missing ranges are fetched; duplicates are merged."
        override val parameters = schema(listOf("symbol"), symbolProp, intervalProp, fromProp, toProp)
        override suspend fun execute(args: JsonObject): String = try {
            val sym = Symbol.parse(args.s("symbol") ?: args.s("pair"))
            val i = interval(args)
            val (from, to) = range(args, i)
            val r = downloader().download(sym, i, from, to)
            summary(r).toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_history_download failed") }
    }

    private fun list() = object : AgentTool {
        override val name = "crypto_history_list"
        override val description = "List cached candle files (symbol, interval, first/last date, candle count, gaps, CSV path)."
        override val parameters = schema(emptyList())
        override suspend fun execute(args: JsonObject): String = try {
            val d = downloader()
            buildJsonObject {
                put("ok", true); put("dir", d.cache.dir.absolutePath)
                put("files", JsonArray(d.cache.list().mapNotNull { (n, f) ->
                    val sym = runCatching { Symbol.parse(n.substringBefore('_')) }.getOrNull() ?: return@mapNotNull null
                    val i = Interval.parse(n.substringAfter('_')) ?: return@mapNotNull null
                    val c = d.cache.load(sym, i)
                    buildJsonObject {
                        put("symbol", sym.id); put("interval", i.code); put("candles", c.size)
                        c.firstOrNull()?.let { put("first", HistoryTime.iso(it.time)) }; c.lastOrNull()?.let { put("last", HistoryTime.iso(it.time)) }
                        put("gaps", CandleCache.gaps(c, i).size); put("path", f.absolutePath)
                    }
                }))
            }.toString()
        } catch (e: Exception) { errorJson(e.message ?: "crypto_history_list failed") }
    }

    private fun backtest() = object : AgentTool {
        override val name = "crypto_backtest"
        override val description = "Offline backtest on cached candles (missing ranges are downloaded first, no API key). Long-only, signals on " +
            "bar close, fills at next open with fee + slippage. Strategies: sma_cross(fast,slow), ema_cross(fast,slow), rsi(period,oversold,overbought), " +
            "bollinger(period,stddev), buy_hold; strategy=custom returns the cached CSV path + a pandas template to run with termux_python. " +
            "Returns total return, CAGR, max drawdown, Sharpe, win rate, trades, buy & hold comparison and an equity chart. Not financial advice."
        override val parameters = schema(emptyList(), symbolProp, intervalProp, fromProp, toProp,
            "strategy" to prop("string", "sma_cross (default), ema_cross, rsi, bollinger, buy_hold, custom"),
            "fast" to prop("integer", "Fast MA period (default 10)"), "slow" to prop("integer", "Slow MA period (default 30)"),
            "period" to prop("integer", "RSI/Bollinger period (14 / 20)"), "oversold" to prop("number", "RSI buy level (30)"),
            "overbought" to prop("number", "RSI sell level (70)"), "stddev" to prop("number", "Bollinger band width (2)"),
            "capital" to prop("number", "Initial capital in quote currency (default 10000)"),
            "fee_percent" to prop("number", "Fee per fill in % (default 0.1)"), "slippage_percent" to prop("number", "Slippage per fill in % (default 0.05)"),
            "position_percent" to prop("number", "% of cash used per entry (default 100)"),
            "save_csv" to prop("boolean", "Also write the equity curve CSV to Output/ (default false)"),
            "pair" to prop("string", "Legacy alias of symbol"), "granularity_seconds" to prop("integer", "Legacy alias of interval in seconds"),
            "fee_pct" to prop("number", "Legacy: fee as a fraction (0.001 = 0.1%)"))
        override suspend fun execute(args: JsonObject): String = try {
            val sym = Symbol.parse(args.s("symbol") ?: args.s("pair") ?: "BTC-USDT")
            val i = interval(args)
            val (from, to) = range(args, i)
            val d = downloader()
            val dl = d.download(sym, i, from, to)
            val candles = d.cache.load(sym, i).filter { it.time in from until to }
            val strategy = args.s("strategy")?.lowercase() ?: "sma_cross"
            if (strategy == "custom") customTemplate(dl, from, to).toString()
            else {
                if (candles.size < 2) throw IllegalStateException("no cached candles for ${sym.id} ${i.code} in range; download errors: ${dl.errors.joinToString("; ")}")
                val params = listOf("fast", "slow", "period", "oversold", "overbought", "stddev").mapNotNull { k -> args.n(k)?.let { k to it } }.toMap()
                val fee = args.n("fee_percent") ?: args.n("fee_pct")?.let { it * 100 } ?: 0.1
                val cfg = BacktestEngine.Config(args.n("capital") ?: 10_000.0, fee, args.n("slippage_percent") ?: 0.05, args.n("position_percent") ?: 100.0)
                val r = BacktestEngine.run(candles, strategy, params, cfg, i.seconds)
                val csv = if (args["save_csv"]?.jsonPrimitive?.booleanOrNull == true) outputDir()?.let { o ->
                    o.mkdirs(); File(o, "backtest-${sym.id}-${i.code}-$strategy.csv").also { f ->
                        f.writeText("time,equity\n" + r.equity.joinToString("\n") { "${HistoryTime.iso(it.first)},${it.second}" }) } } else null
                report(r, sym, i, cfg, dl, csv).toString()
            }
        } catch (e: Exception) { errorJson(e.message ?: "crypto_backtest failed") }
    }

    private fun report(r: BacktestEngine.Result, sym: Symbol, i: Interval, cfg: BacktestEngine.Config, dl: DownloadResult, csv: File?) = buildJsonObject {
        put("ok", true); put("strategy", r.strategy); put("symbol", sym.id); put("pair", sym.id); put("interval", i.code)
        put("from", HistoryTime.iso(r.equity.first().first)); put("to", HistoryTime.iso(r.equity.last().first)); put("candles", r.bars)
        put("initial_capital", cfg.capital); put("final_equity", r2(r.finalEquity))
        put("total_return_pct", r2(r.totalReturnPct)); put("cagr_pct", r2(r.cagrPct)); put("max_drawdown_pct", r2(r.maxDrawdownPct))
        put("sharpe", r2(r.sharpe)); put("win_rate_pct", r2(r.winRatePct)); put("trades", r.trades.size)
        put("buy_hold_return_pct", r2(r.buyHoldReturnPct)); put("vs_buy_hold_pct", r2(r.totalReturnPct - r.buyHoldReturnPct))
        put("fee_percent", cfg.feePct); put("slippage_percent", cfg.slippagePct); put("position_percent", cfg.positionPct)
        put("data_sources", JsonArray(dl.sources.map(::JsonPrimitive))); put("gaps", dl.gaps.size)
        if (dl.errors.isNotEmpty()) put("download_errors", JsonArray(dl.errors.take(5).map(::JsonPrimitive)))
        put("trade_list", JsonArray(r.trades.takeLast(20).map { t -> buildJsonObject {
            put("entry_time", HistoryTime.iso(t.entryTime)); put("exit_time", HistoryTime.iso(t.exitTime))
            put("entry", r2(t.entry)); put("exit", r2(t.exit)); put("return_pct", r2(t.returnPct)) } }))
        val step = maxOf(1, r.equity.size / 60); val eq = r.equity.filterIndexed { k, _ -> k % step == 0 || k == r.equity.lastIndex }
        put("equity_chart", buildJsonObject {
            put("type", "line"); put("title", "Equity · ${r.strategy} · ${sym.id} ${i.code}"); put("x_label", "time"); put("y_label", "equity")
            put("labels", JsonArray(eq.map { JsonPrimitive(HistoryTime.day(it.first)) }))
            put("series", JsonArray(listOf(buildJsonObject { put("name", "equity"); put("values", JsonArray(eq.map { JsonPrimitive(r2(it.second)) })) })))
        })
        csv?.let { put("equity_csv", it.absolutePath) }
        put("note", "Pass equity_chart to the chart tool to plot it (it can export a PNG). Past performance is not indicative; not financial advice.")
    }

    private fun customTemplate(dl: DownloadResult, from: Long, to: Long) = buildJsonObject {
        put("ok", true); put("strategy", "custom"); put("csv_path", dl.file.absolutePath); put("candles", dl.total)
        put("columns", CandleCache.HEADER)
        put("how", "Run your own strategy with termux_python (pandas) on csv_path. The CSV is in Documents/Verdroid/Internal/market-data " +
            "(Termux needs termux-setup-storage). Write results/plots to os.environ['VERDROID_OUTPUT'].")
        put("python_template", """
import pandas as pd, numpy as np
df = pd.read_csv("${dl.file.absolutePath}")
df = df[(df.time >= $from) & (df.time < $to)].reset_index(drop=True)
df["sig"] = (df.close.rolling(10).mean() > df.close.rolling(30).mean()).astype(int)   # <- your rule
df["pos"] = df.sig.shift(1).fillna(0)                      # trade next bar
ret = df.close.pct_change().fillna(0) * df.pos - df.pos.diff().abs().fillna(0) * 0.001
eq = (1 + ret).cumprod()
print({"total_return_pct": (eq.iloc[-1]-1)*100, "max_drawdown_pct": ((eq.cummax()-eq)/eq.cummax()).max()*100})
""".trim())
    }

    private fun summary(r: DownloadResult) = buildJsonObject {
        put("ok", r.total > 0); put("symbol", r.symbol); put("interval", r.interval); put("added", r.added); put("total_cached", r.total)
        r.first?.let { put("first", HistoryTime.iso(it)) }; r.last?.let { put("last", HistoryTime.iso(it)) }
        put("sources", JsonArray(r.sources.map(::JsonPrimitive))); put("gaps", r.gaps.size)
        if (r.gaps.isNotEmpty()) put("gap_examples", JsonArray(r.gaps.take(5).map { JsonPrimitive("${HistoryTime.iso(it.first)} .. ${HistoryTime.iso(it.second)}") }))
        if (r.errors.isNotEmpty()) put("errors", JsonArray(r.errors.take(5).map(::JsonPrimitive)))
        put("path", r.file.absolutePath)
        if (r.total == 0) put("error", "no candles found (unknown symbol or all sources failed)")
    }

    private fun interval(args: JsonObject): Interval {
        val raw = args.s("interval") ?: args["granularity_seconds"]?.jsonPrimitive?.contentOrNull ?: "1d"
        return Interval.parse(raw) ?: throw IllegalArgumentException("interval must be one of ${Interval.CODES}")
    }
    /** Fast preflight `python3 -c 'import pandas, numpy, matplotlib'`; a positive result is cached for the process. */
    private suspend fun depsReady(t: com.verdroid.app.data.termux.TermuxRunner): Boolean {
        if (depsOk) return true
        val r = t.runAndWait(PREFLIGHT, "btdeps-${System.nanoTime()}", 25_000, label = "Verdroid: backtest check")
        depsOk = preflightOk(r?.stdout)
        return depsOk
    }

    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun termuxPath(f: com.verdroid.app.data.storage.SharedFolder, file: File) =
        com.verdroid.app.data.storage.SharedFolder.DISPLAY_PATH + "/" + file.relativeTo(f.root).path
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun r2(x: Double) = round(x * 100) / 100

    private class NeedsSetup : Exception()

    companion object {
        const val MAX_BARS = 500_000L
        const val HARNESS_REL = "backtest/verdroid_backtest.py"
        const val MARK = "@@VERDROID_BACKTEST@@"
        @Volatile private var depsOk = false
        internal const val PREFLIGHT = "python3 -c '${com.verdroid.app.data.tools.TermuxPackages.BACKTEST_IMPORT}' >/dev/null 2>&1 && echo DEPS=1 || echo DEPS=0"
        internal fun preflightOk(stdout: String?) = stdout?.contains("DEPS=1") == true
        internal fun resetPreflightCache() { depsOk = false }

        /** Returned instead of installing from chat: the user installs the libraries in Settings. */
        val NEEDS_SETUP: JsonObject = buildJsonObject {
            put("error", "Python libraries for crypto backtesting (pandas, numpy, matplotlib) are not installed.")
            put("needs_setup", true)
            put("tell_user", "Go to Settings → Tools → Available to install and tap Install for crypto-backtest (Crypto backtesting). Then ask me again.")
            put("next_step", "Tell the user exactly that, then STOP and wait for them. Do not run crypto_backtest or any other fallback " +
                "backtest unless the user explicitly asks for it. Do not install packages from the chat (no pip/pkg via termux_run).")
        }

        /** Harness shipped in app resources (src/main/resources/verdroid/verdroid_backtest.py). */
        fun harnessSource(): String = CryptoHistoryTools::class.java.getResourceAsStream("/verdroid/verdroid_backtest.py")!!
            .bufferedReader().use { it.readText() }

        fun strategyName(raw: String?): String {
            val n = raw?.trim()?.removeSuffix(".py")?.substringAfterLast('/').orEmpty()
            require(n.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "strategy must be a simple name like rsi_dip (letters, digits, _ or -)" }
            return n
        }

        internal fun scriptCommand(harness: String, strategy: String, csv: String, outDir: String, name: String, symbol: String,
                                   interval: Interval, from: Long, to: Long, params: JsonObject, capital: Double, fee: Double,
                                   slip: Double, position: Double, allowShort: Boolean, plot: Boolean, timeoutS: Int): String {
            val q = TermuxRunTool::shellQuote
            val argv = listOf("--strategy", strategy, "--csv", csv, "--out_dir", outDir, "--name", name, "--symbol", symbol,
                "--interval", interval.code, "--start", from.toString(), "--end", to.toString(), "--bar_seconds", interval.seconds.toString(),
                "--params", params.toString(), "--capital", capital.toString(), "--fee_percent", fee.toString(),
                "--slippage_percent", slip.toString(), "--position_percent", position.toString(),
                "--allow_short", allowShort.toString(), "--plot", plot.toString())
            return TermuxRunTool.WORKDIR + TermuxRunTool.capped("python3 " + q(harness) + " " + argv.joinToString(" ") { q(it) }, timeoutS)
        }

        /** Takes the harness' marker line; adds hints for missing packages / crashes. */
        internal fun parseHarnessOutput(stdout: String, stderr: String, exit: Int, strategyRel: String, dl: DownloadResult?): JsonObject {
            val line = stdout.lineSequence().lastOrNull { it.startsWith(MARK) }
            val base = line?.let { runCatching { Json.parseToJsonElement(it.removePrefix(MARK)).jsonObject }.getOrNull() }
            return buildJsonObject {
                if (base == null) {
                    put("error", if (exit == 127) "python3 not found in Termux (pkg install python)" else "backtest harness gave no result (exit $exit)")
                    put("stdout", stdout.takeLast(3000)); put("stderr", stderr.takeLast(3000))
                } else {
                    base.forEach { (k, v) -> put(k, v) }
                    if (base["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                        put("error", base["error"] ?: JsonPrimitive("backtest failed"))
                        if (base["missing_packages"]?.jsonPrimitive?.booleanOrNull == true) {
                            resetPreflightCache()
                            put("hint", NEEDS_SETUP["tell_user"]!!)
                        }
                    }
                    val logs = stdout.lineSequence().filterNot { it.startsWith(MARK) }.joinToString("\n").trim()
                    if (logs.isNotEmpty()) put("strategy_output", logs.takeLast(3000))
                }
                put("strategy_file", strategyRel)
                dl?.let { put("data_sources", JsonArray(it.sources.map(::JsonPrimitive))); put("gaps", it.gaps.size) }
                put("note", "Signals are applied on the next bar. Beware of overfitting: check other periods/symbols before trusting a result. Not financial advice.")
            }
        }

        /** Production wiring: OkHttp GET, cache in Internal/market-data (or app files when no shared access). */
        fun androidDownloader(cacheDir: File): HistoryDownloader {
            val http = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            val get: HttpGet = { url ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Verdroid").build()).execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}: ${body.take(160)}")
                        body
                    }
                }
            }
            return HistoryDownloader(CandleCache(cacheDir), listOf(BinanceHistory(get), CoinbaseHistory(get), KrakenHistory(get)))
        }
    }
}
