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
npm install --no-audit --no-fund || npm install --no-audit --no-fund
[ -x node_modules/.bin/concurrently ] || { echo "ERROR: npm install did not finish (see the first non-optional error above)"; exit 1; }
[ -d node_modules/esbuild ] && (npm rebuild esbuild >/dev/null 2>&1 || true)
echo "Note: a warning about the optional better-sqlite3 package is harmless on Android."
echo "DONE. Go back to Verdroid and tap Start."
) 2>&1 | tee -a "${'$'}L/install.log"
""".trimStart()

    /** Background start (dev mode, the only documented way; dashboard + API both on localhost). */
    fun startScript(): String = PRELUDE + """
[ -f "${'$'}D/package.json" ] && [ -d "${'$'}D/node_modules" ] || { echo $NOT_INSTALLED; exit 3; }
p=${'$'}(cat "${'$'}L/server.pid" 2>/dev/null)
if [ -n "${'$'}p" ] && kill -0 "${'$'}p" 2>/dev/null; then echo $ALREADY_RUNNING; exit 0; fi
termux-wake-lock 2>/dev/null
cd "${'$'}D" || exit 1
echo "== start ${'$'}(date)" > "${'$'}L/server.log"
if command -v setsid >/dev/null; then setsid nohup npm run dev >>"${'$'}L/server.log" 2>&1 </dev/null &
else nohup npm run dev >>"${'$'}L/server.log" 2>&1 </dev/null & fi
echo ${'$'}! > "${'$'}L/server.pid"
echo $STARTED
""".trimStart()

    fun stopScript(): String = PRELUDE + """
p=${'$'}(cat "${'$'}L/server.pid" 2>/dev/null)
[ -n "${'$'}p" ] && { kill -- -"${'$'}p" 2>/dev/null || kill "${'$'}p" 2>/dev/null; }
pkill -f "${'$'}D/node_modules" 2>/dev/null
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
        NOT_INSTALLED in stdout -> "FreeLLMAPI isn't installed yet. Tap Install / Update first."
        ALREADY_RUNNING in stdout -> "Already running."
        STARTED in stdout -> "Starting… the first start takes up to a minute. Then open the dashboard."
        else -> "Start failed: " + stdout.trim().take(200)
    }

    const val HELP = "Experimental. FreeLLMAPI is a free, open-source gateway that pools free tiers (Groq, Cerebras, Mistral, Gemini, " +
        "OpenRouter free models, …) behind one key, with failover. It runs in Termux on this phone (≈1 GB storage, a few hundred MB RAM).\n" +
        "1. Install / Update (opens Termux, takes a few minutes).  2. Start.  3. Open dashboard, add your free provider keys there " +
        "and copy its unified API key.  4. Paste that key below, Save, then add models.\n" +
        "Keep it running: turn off battery optimization for Termux. On Android 12+, if it gets killed in the background, enable " +
        "Developer options → \"Disable child process restrictions\". It only listens on this phone (localhost)."
}
