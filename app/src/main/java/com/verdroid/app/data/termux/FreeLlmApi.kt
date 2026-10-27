package com.verdroid.app.data.termux

/**
 * EXPERIMENTAL: FreeLLMAPI (github.com/tashfeenahmed/freellmapi, MIT) run inside Termux as a local
 * OpenAI-compatible gateway. Verdroid only installs/starts/stops it through Termux RUN_COMMAND and then talks to
 * it like any custom provider at [BASE_URL]. Pure script builders (unit-tested; no Android types).
 */
object FreeLlmApi {
    const val PROVIDER_ID = "freellmapi"
    const val REPO = "https://github.com/tashfeenahmed/freellmapi.git"
    const val BASE_URL = "http://127.0.0.1:3001/v1/"
    const val DASHBOARD_URL = "http://localhost:5173"
    const val MIN_NODE = 22_013 // major*1000+minor: node:sqlite needs 22.13+
    private const val DIR = "\$HOME/freellmapi"
    private const val LOGS = "\$HOME/.verdroid/freellmapi"

    const val NOT_INSTALLED = "FREELLMAPI_NOT_INSTALLED"
    const val STARTED = "FREELLMAPI_STARTED"
    const val ALREADY_RUNNING = "FREELLMAPI_ALREADY_RUNNING"
    const val STOPPED = "FREELLMAPI_STOPPED"

    private const val PRELUDE = "D=$DIR; L=$LOGS; mkdir -p \"\$L\"\n"

    /** Idempotent install-or-update, shown in a Termux window and appended to install.log. */
    fun installScript(): String = PRELUDE + """
(
set -o pipefail
echo "== FreeLLMAPI install/update ${'$'}(date)"
pkg update -y && pkg install -y nodejs-lts git || { echo "ERROR: pkg install failed (check your internet / Termux mirrors)"; exit 1; }
nv=${'$'}(node -e 'const [a,b]=process.versions.node.split(".").map(Number);console.log(a*1000+b)' 2>/dev/null || echo 0)
if [ "${'$'}nv" -lt $MIN_NODE ]; then echo "ERROR: Node ${'$'}(node --version 2>/dev/null) is too old; FreeLLMAPI needs 22.13+. Run: pkg upgrade -y"; exit 1; fi
if [ -d "${'$'}D/.git" ]; then
  echo "Updating ${'$'}D"; git -C "${'$'}D" pull --ff-only || { echo "ERROR: git pull failed (local changes in ${'$'}D?)"; exit 1; }
elif [ -e "${'$'}D" ]; then
  echo "ERROR: ${'$'}D exists but is not a git checkout; move it away and retry"; exit 1
else
  git clone --depth 1 $REPO "${'$'}D" || { echo "ERROR: git clone failed"; exit 1; }
fi
cd "${'$'}D" || exit 1
unset NODE_ENV npm_config_production npm_config_omit
export NODE_ENV=development
# Dev deps (concurrently, tsx, vite) are needed: the documented Android start is dev mode. Don't omit optional deps:
# esbuild/rollup ship their Android binaries as optional packages (better-sqlite3 is optional and unused on Android).
fla_ok() { for b in concurrently tsx vite; do [ -x node_modules/.bin/${'$'}b ] || [ -x server/node_modules/.bin/${'$'}b ] || [ -x client/node_modules/.bin/${'$'}b ] || { echo "missing: ${'$'}b"; return 1; }; done; }
npm install --include=dev --include=optional --no-audit --no-fund
if ! fla_ok; then echo "Some tools are missing, retrying npm install once…"; npm install --include=dev --include=optional --no-audit --no-fund; fi
fla_ok || { echo "ERROR: npm install did not finish: concurrently/tsx/vite are missing (see the first non-optional error above). Check free storage, then tap Install / Update again."; exit 1; }
[ -d node_modules/esbuild ] && (npm rebuild esbuild >/dev/null 2>&1 || true)
echo "Note: a warning about the optional better-sqlite3 package is harmless on Android."
echo "DONE. Go back to Verdroid and tap Start."
) 2>&1 | tee -a "${'$'}L/install.log"
rc=${'$'}{PIPESTATUS[0]}
echo
if [ "${'$'}rc" = 0 ]; then echo "RESULT: OK. Go back to Verdroid and tap Start."; else echo "RESULT: FAILED (exit ${'$'}rc). Scroll up for the ERROR line; the log is in ${'$'}L/install.log"; fi
echo "Press Enter to close this window."
read -r _
""".trimStart()

    /** Background start (dev mode, the only documented way; dashboard + API both on localhost). */
    const val DIED = "FREELLMAPI_DIED"

    /**
     * Full Termux environment: background RUN_COMMAND sessions may lack PATH/TMPDIR and termux-exec (LD_PRELOAD),
     * without which `#!/usr/bin/env node` shebangs fail with "not found".
     */
    private val ENV = """
export PREFIX=/data/data/com.termux/files/usr HOME=/data/data/com.termux/files/home
export TMPDIR="${'$'}PREFIX/tmp" LANG="${'$'}{LANG:-en_US.UTF-8}"
export PATH="${'$'}D/node_modules/.bin:${'$'}PREFIX/bin:${'$'}PATH"
if [ -z "${'$'}LD_PRELOAD" ]; then for f in "${'$'}PREFIX/lib/libtermux-exec-ld-preload.so" "${'$'}PREFIX/lib/libtermux-exec.so"; do [ -f "${'$'}f" ] && { export LD_PRELOAD="${'$'}f"; break; }; done; fi
export npm_config_script_shell="${'$'}PREFIX/bin/bash" SHELL="${'$'}PREFIX/bin/bash"
unset NODE_ENV npm_config_production npm_config_omit
""".trimStart()

    /** Same as the repo's `npm run dev`, but the top-level concurrently runs via `node` explicitly (no shebang). */
    private val DEV_CMD = """
cj=${'$'}(node -p 'const p=require("./node_modules/concurrently/package.json");typeof p.bin=="string"?p.bin:(p.bin.concurrently||p.bin.conc)' 2>/dev/null)
if [ -n "${'$'}cj" ] && [ -f "node_modules/concurrently/${'$'}cj" ]; then set -- node "node_modules/concurrently/${'$'}cj"; else set -- npx --no-install concurrently; fi
""".trimStart()
    private const val DEV_ARGS = "--kill-others-on-fail --names server,client \"npm run dev -w server\" \"npm run dev -w client\""

    private val DIAG = """
{ echo "== start ${'$'}(date)"; echo "uid=${'$'}(id -u) PATH=${'$'}PATH"; echo "LD_PRELOAD=${'$'}LD_PRELOAD"
  for c in node npm sh bash; do echo "${'$'}c: ${'$'}(command -v ${'$'}c)"; done; node --version
  ls -la node_modules/.bin/concurrently; echo "target: ${'$'}(readlink -f node_modules/.bin/concurrently)"
  echo "head: ${'$'}(head -c 80 node_modules/.bin/concurrently | head -n 1)"; ls -la /usr/bin/env 2>&1
  echo "cmd: ${'$'}* $DEV_ARGS"; echo "=="; } > "${'$'}L/server.log" 2>&1
""".trimStart()

    /** Background start: full env, diagnostics, then checks after 5 s that it's still alive. */
    fun startScript(): String = PRELUDE + """
[ -f "${'$'}D/package.json" ] && [ -x "${'$'}D/node_modules/.bin/concurrently" ] || { echo $NOT_INSTALLED; exit 3; }
p=${'$'}(cat "${'$'}L/server.pid" 2>/dev/null)
if [ -n "${'$'}p" ] && kill -0 "${'$'}p" 2>/dev/null; then echo $ALREADY_RUNNING; exit 0; fi
""" + ENV + """
termux-wake-lock 2>/dev/null
cd "${'$'}D" || exit 1
""" + DEV_CMD + DIAG + """
if command -v setsid >/dev/null; then setsid nohup "${'$'}@" $DEV_ARGS >>"${'$'}L/server.log" 2>&1 </dev/null &
else nohup "${'$'}@" $DEV_ARGS >>"${'$'}L/server.log" 2>&1 </dev/null & fi
pid=${'$'}!
echo ${'$'}pid > "${'$'}L/server.pid"
sleep 5
if kill -0 ${'$'}pid 2>/dev/null; then echo $STARTED; else echo $DIED; tail -n 15 "${'$'}L/server.log"; fi
""".trimStart()

    /** Fallback: foreground in a visible Termux window (the interactive env reliably works); window stays open. */
    fun startInTerminalScript(): String = PRELUDE + """
if [ ! -x "${'$'}D/node_modules/.bin/concurrently" ]; then echo "FreeLLMAPI isn't fully installed. Run Install / Update in Verdroid."; echo "Press Enter to close."; read -r _; exit 3; fi
""" + ENV + """
termux-wake-lock 2>/dev/null
cd "${'$'}D" || exit 1
""" + DEV_CMD + DIAG + """
echo "Running FreeLLMAPI here. Keep this window open; press Ctrl+C to stop."
"${'$'}@" $DEV_ARGS 2>&1 | tee -a "${'$'}L/server.log"
termux-wake-unlock 2>/dev/null
echo; echo "FreeLLMAPI stopped (see above). Press Enter to close this window."
read -r _
""".trimStart()

    fun stopScript(): String = PRELUDE + """
p=${'$'}(cat "${'$'}L/server.pid" 2>/dev/null)
[ -n "${'$'}p" ] && { kill -- -"${'$'}p" 2>/dev/null || kill "${'$'}p" 2>/dev/null; }
pkill -f "${'$'}D/node_modules" 2>/dev/null
pkill -f "concurrently.* --names server,client" 2>/dev/null
: > "${'$'}L/server.pid"
termux-wake-unlock 2>/dev/null
echo $STOPPED
""".trimStart()

    fun logScript(lines: Int = 60): String = PRELUDE +
        "echo '--- install.log'; tail -n $lines \"\$L/install.log\" 2>/dev/null || echo '(none yet)'; " +
        "echo '--- server.log'; tail -n $lines \"\$L/server.log\" 2>/dev/null || echo '(none yet)'\n"

    /** Maps a start script stdout to a short user message. */
    fun startMessage(stdout: String?): String = when {
        stdout == null -> "Termux didn't answer. Is Termux set up (Settings → Tools → Termux)?"
        NOT_INSTALLED in stdout -> "FreeLLMAPI isn't (fully) installed: its dev tools are missing. Run Install / Update again."
        ALREADY_RUNNING in stdout -> "Already running."
        DIED in stdout -> "It stopped right away. Last log lines:\n" + stdout.substringAfter(DIED).trim().takeLast(1200) +
            "\nTry \"Start in Termux window\" to watch it run."
        STARTED in stdout -> "Starting… the first start takes up to a minute. Then open the dashboard."
        else -> "Start failed: " + stdout.trim().take(200)
    }

    const val HELP = "Experimental. FreeLLMAPI is a free, open-source gateway that pools free tiers (Groq, Cerebras, Mistral, Gemini, " +
        "OpenRouter free models, …) behind one key, with failover. It runs in Termux on this phone (≈1 GB storage, a few hundred MB RAM). " +
        "Install switches Termux's Node.js to the LTS package (nodejs-lts replaces nodejs).\n" +
        "1. Install / Update (opens Termux, takes a few minutes).  2. Start.  3. Open dashboard, add your free provider keys there " +
        "and copy its unified API key.  4. Paste that key below, Save, then add models.\n" +
        "Keep it running: turn off battery optimization for Termux. On Android 12+, if it gets killed in the background, enable " +
        "Developer options → \"Disable child process restrictions\". It only listens on this phone (localhost)."
}
