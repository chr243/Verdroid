# Verdroid

[![GitHub release](https://img.shields.io/github/v/release/chr243/Verdroid?label=GitHub%20release)](https://github.com/chr243/Verdroid/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Also listed in [ARK-Store](https://github.com/topics/arkstore) (GitHub topic `arkstore`).

**An on-device AI agent for Android that actually does things:** it searches the web and fetches pages and APIs over HTTP, runs bash and Python in Termux (optionally with headless Chromium + Selenium), runs shell commands through Shizuku or rish, works with files in `Documents/Verdroid` and Git, translates ebooks, and backtests crypto trading strategies — all tuned for **free OpenRouter / Kilo models**, with automatic model and key fallback and rate-limit recovery, and with your own API keys (BYOK) when you want them. No server and no cloud backend: your keys stay encrypted on your phone. Driving other apps with Accessibility is optional.

## Highlights

- **⚡ Free models that keep going.** Tool calling on OpenRouter free models and Kilo (no key), with a Model priority list, automatic fallback across models and API keys, backoff and auto-resume after rate limits. Bring your own keys for OpenRouter or any OpenAI-compatible API (OpenAI, Groq, DeepSeek, Together, Ollama, LM Studio, Custom) — they stay encrypted on the device.
- **📈 Crypto strategy backtesting, just by chatting.** Describe your idea ("buy when RSI dips below 30 on BTC 4h, sell at 55"). The agent asks about entries, exits and risk, writes the strategy as Python and runs it offline on free historical candles (Binance public data, Coinbase/Kraken fallback, no API key, cached locally). It reports return, CAGR, max drawdown, Sharpe, win rate and trades vs buy & hold, plus an equity chart (PNG/CSV in `Output/backtests/`). Strategies are saved so you can tweak and re-run them. Signals are applied on the next bar (no lookahead), with fees and slippage included. You install the Python libraries once with a single fast tap in Settings → Tools (*crypto-backtest*). It uses prebuilt Termux packages, so there's no slow compiling on the phone. *Not financial advice. Backtests can overfit.*
- **📚 Whole-book translation on your phone.** Drop a MOBI, EPUB, PDF, DOCX or TXT into `Documents/Verdroid/Input` and ask for a language. `ebook_translate` gives you an ETA first, then translates chapter by chapter in Termux Python. It uses Google Translate with a MyMemory fallback, checks the language, handles rate limits and resumes if interrupted. The result lands in `Output/` as TXT (or DOCX). No API key.
- **🐦 X / Twitter, opt-in.** With your own X login (signed in once in an in-app browser), the agent can read your timeline, a profile or a search, and write a post or reply. You see the exact text and tap Post before anything is published. Turning on the Twitter group shows a warning first: automating X with your login is against X's Terms of Service and may get the account limited or banned. Needs the optional headless Chromium add-on in Termux.
- **📤 Share to Verdroid from any app.** Share a link, text or a document and pick Summarize, Translate or Ask (X profile links start with "Who is this person?"), into a new chat or your last one. With "Display over other apps" the share card collapses into the chat bubble and the reply arrives there. [More](#share-to-verdroid)

**Best for:** research with real web search and fetch, files and Git in `Documents/Verdroid`, scripting via Termux (bash/Python, optional Selenium), ebook translation, crypto strategy backtesting, and any workflow that needs real tools plus your own API keys. Phone UI automation (Accessibility) is optional.

| Chats | Chat thread |
|:---:|:---:|
| ![Verdroid chat list](docs/images/chat-list.jpg) | ![Verdroid chat thread running ebook_translate](docs/images/chat-thread.jpg) |

## Features

- **Messenger-style chat UI** (Kotlin, Jetpack Compose, Material 3) with chat heads / Android Bubbles and a quota indicator. Chat avatars are circles in shades of the theme green (lighter/darker per chat, theme-aware light/dark). Thinking and tool steps between replies collapse into one "N steps" row (spinner while running, ✅ or ❌ when done) that expands into the individual tool cards; the chat head badge counts only replies to you.
- **Free-model agent loop:** tool calling on OpenRouter free models and Kilo, priority list, automatic fallback across models and API keys, backoff and auto-resume after rate limits.
- **Anti-looping safeguards:** hard max steps (default 25, adjustable in Settings) that pauses with “Tap Continue”; nudges for empty/silent replies, bad or truncated tool calls, and tool crashes; recovery from rate limits instead of thrashing. Identical successful tool calls can still repeat until the step limit.
- **Any OpenAI-compatible API:** Settings → LLM providers. OpenRouter (multiple keys) and Kilo (free, no key) plus OpenAI, Groq, DeepSeek, Together, Ollama, LM Studio and *Custom*. Each provider is set up separately (encrypted key; server address and extra headers under *Advanced*), has *Test connection*, and its own model list. Models join Model priority tagged with their provider, e.g. `llama-3.1-8b-instant (Groq)`, and every request goes to that provider. `http://` works only for `localhost`/`*.local`.
- **Web search (default):** `web_search`, a keyless multi-engine search ported from [hec-ovi/websearch-skill](https://github.com/hec-ovi/websearch-skill) (MIT): DuckDuckGo (html, with lite fallback), Brave, Bing, Mojeek, Yahoo and Wikipedia queried in parallel over plain HTTP, redirect links unwrapped (`duckduckgo.com/l/?uddg=`, Bing `ck/a`, Yahoo `/RU=`), URLs canonicalised and deduplicated, then ranked with de-correlated reciprocal-rank fusion. No browser, so it is fast and avoids most captchas; a blocked engine just drops out.
- **Web fetching:** `web_fetch`, a plain HTTP GET/HEAD (no browser, no JavaScript) for a known URL or API. `format=markdown` returns a clean Markdown extract of the page, paginated by tokens (`page`, `page_size_tokens`, cached), wrapped in an untrusted-content fence, with block/captcha detection.
- **Crypto backtesting (no API key):** talk through a strategy with the agent; it writes it as Python (`generate_signals(df, params)`, saved in `Documents/Verdroid/Internal/strategies/`) and runs it with `crypto_backtest_script` on cached candles: no lookahead, fees, slippage, position sizing, return/CAGR/max drawdown/Sharpe/win rate vs buy & hold, equity CSV/PNG in `Output/backtests/`. History is downloaded keylessly (Binance public data → Coinbase Exchange → Kraken) into `Internal/market-data/` and only missing ranges are fetched (`crypto_history_download`, `crypto_history_list`). Quick built-ins without Termux: `crypto_backtest` (SMA/EMA cross, RSI, Bollinger, buy & hold). Backtests are not financial advice.
- **Shell and Termux:** `run_shell` via Shizuku (shell uid), `rish_run`, and `termux_run` (bash in Termux through its RUN_COMMAND service, cwd `Documents/Verdroid/Internal`, falling back to `~/verdroid-work` when Termux has no storage access) with optional Termux packages (ffmpeg, imagemagick, yt-dlp, jq, curl, pandoc, …) installable from Settings → Tools → Available to install. `termux_run` returns as soon as the command finishes; `timeout_seconds` is only an upper limit (background jobs left running are stopped).
- **Screen control (optional):** `screen_*` tools via an Accessibility service to read and tap other apps' UI. Enable only if you want phone UI automation; everything else works without it.
- **Headless Chromium + Selenium (in Termux):** the optional `chromium-selenium` add-on (Settings → Tools → Available to install: `x11-repo`/`tur-repo` Chromium + chromedriver, `pip install selenium`) powers `selenium_open` (title, visible text, links), `selenium_page_source` (rendered HTML) and `selenium_screenshot` (PNG), all headless, plus `termux_python` to run Python scrapers the agent writes. Scripts stay in the private app workspace (e.g. `scrapers/*.py`); scraped files go to `Documents/Verdroid/Output` (run `termux-setup-storage` in Termux once). Use it for JavaScript-heavy pages; `web_search`/`web_fetch` stay the default.
- **Shared folder:** `/storage/emulated/0/Documents/Verdroid` with `Input/` (files you give Verdroid), `Output/` (its results: translations, screenshots, scripts, projects) and `Internal/` (the agent's helper scripts, strategies, cached market data and temp files, so the root stays clean), created at launch once *All files access* is granted (Settings → Tools → Shared folder, or Settings → Phone → Permissions). `workspace_list` / `workspace_read` / `workspace_write` / `workspace_delete` can only reach that tree (path escapes and symlinks are rejected).
- **Per-chat model:** the chat ⋮ menu has **Model**: use the global Model priority (default) or pin one model for that chat (OpenRouter free or paid, Kilo, your own providers, local). A pinned chat uses only that model and shows an error instead of switching (optional *Allow fallback*); the header shows 📌 and the model.
- **Schedules:** ask "remind me in 10 minutes about my soup" (a plain notification, no AI call), "every day at 10am check cnn.com and give me a summary" or "every 15 minutes check this blog, only tell me if there's a new post". Scheduled runs happen without you in the chat where you asked (or in a separate chat if you ask for one) and post a notification with the summary; tools that need your confirmation (posting, deleting, installing, phone control) are blocked in them. Settings → Schedules lists them with next run, last result, on/off, Run now and Delete. Repeats are at least 15 minutes apart, and battery optimisation can delay runs (Verdroid tells you how to allow background activity).
- **Logged-in pages (beta, Web group, off by default):** Settings → Tools → Logged-in sites → *Log in to a site* opens an in-app browser; once you are logged in the cookies are saved encrypted and `cookie_open` reads pages with them in headless Chromium. A cookie export file (`cookie_import`) also works. Some sites (e.g. Google) block this kind of login.
- **Twitter (beta, opt-in, against X's ToS):** `x_timeline`, `x_user_posts`, `x_search` return posts as readable lines (author, text, time, link); `x_post` publishes one post or reply only after you confirm it in a Post / Cancel dialog (a "Post to X without asking" switch exists, off by default). "Limit X posts" (on by default) caps posting at 5 per hour and 20 per day, adjustable or switchable off in Settings → Tools → Twitter; every attempt is logged in `Internal/x_post_log.txt`.
- **Reddit (read-only):** the agent searches Reddit and reads threads with `cookie_open`, using your own login (Settings → Tools → Logged-in sites → Log in to Reddit) in headless Chromium. A bot check is reported as blocked, not as an empty result. Without a login or the chromium-selenium add-on it falls back to web search and tells you how to improve it. Scripts can opt in with `termux_python` `cookies_for=["reddit.com"]` + `make_driver(cookies_for="reddit.com")` for several queries in one browser.
- **File change confirmations:** deleting, moving or renaming files, and overwriting existing files outside `Internal/`, needs your Yes. Overwrites (only) can be allowed for one chat or always (Settings → Tools switch).
- **Crash log:** crashes are saved to `Documents/Verdroid/Internal/crash/` and shown in Settings → App → Crash log (with Share).
- **Tool presets per chat:** the chat ⋮ menu has **Tool presets**, grouped like Settings → Tools (including Crypto backtesting). Each group follows Settings by default; turning it on or off in a chat overrides Settings for that chat only. Charts and the self-test are always on.
- **AGENTS.md:** Settings → AGENTS.md holds your standing instructions for the agent; they're added to every chat.
- **Skills:** the agent can save reusable multi-step procedures it worked out with you (`skill_save`, `skill_edit`, `skill_delete`, `skill_list`, `skill_get`) as `files/skills/<id>/SKILL.md` inside the app. Settings → Skills lists them with an on/off switch and Delete; the system prompt only gets an index of enabled skills (name + one-line description, required on save), and the agent loads the full SKILL.md with `skill_get` when a task matches. The agent offers to save a skill when a procedure looks reusable.
- **rish:** in Settings → Shizuku & Git → rish, tap **Find rish** (with All files access it scans Download, Documents and Documents/Verdroid/Input) or **Pick rish file** for the `rish` exported by Shizuku (*Use Shizuku in terminal apps* → Export files; see [Shizuku-API/rish](https://github.com/RikkaApps/Shizuku-API/tree/master/rish)). Verdroid stages `rish` + `rish_shizuku.dex` in its private storage, then copies both to `/data/local/tmp/verdroid_rish/` through Shizuku and `chmod +x` both (**Fix rish permissions** repeats that; needs Shizuku running). The agent runs commands with `rish_run` from that folder with `RISH_APPLICATION_ID=com.termux`. Deliverables (including screenshots) go to `Documents/Verdroid/Output`.
- **Ebook translation:** `ebook_translate` (Termux Python: MOBI first, then EPUB/PDF/DOCX/TXT, plain-text fallback if a reader fails). `googletrans>=4.0.2` with a browser User-Agent, chunks of at most 4000 characters (never 5000+), ~0.3 s between requests with backoff on *Too many requests*, a 5–10 s pause every 4 chapters, MyMemory fallback, per-engine language-code handling, and resume after interruptions. It is two-step: the first call estimates chapters, chunks and time and the agent asks you to confirm before translating. Missing Python packages are installed on first use only after you agree in chat (the **ebook-translate** add-on under Settings → Tools installs them up front). Attach a file in chat or put it in `Documents/Verdroid/Input/`; the result lands in `Output/`.
- **Attach file in chat:** the composer’s **+** picks a file (SAF) and copies it into `Documents/Verdroid/Input`. The chat shows a clean 📎 chip with the file name and size; the path and tool hints are sent to the model only. A chat started with just an attachment is titled after the file.
- **Permissions in one place:** Settings → Phone → Permissions lists everything Verdroid can ask for (All files access, notifications, Termux RUN_COMMAND, Shizuku, overlay, battery optimisation, installing updates, accessibility) with status and a Grant button, plus a shortcut to *Set up Termux*.
- **Git:** clone / status / commit / push with JGit inside the app workspace.
- **Memory, MCP client, sandboxed file tools** and a keep-alive service that only runs while a task runs.
- **Security first:** API keys in EncryptedSharedPreferences (Android Keystore), no backups, HTTPS only.

## Share to Verdroid

Share a link, some text or a document from any app (browser, news app, X, file manager) and pick an action:

- **Summarize** a page (selected for links)
- **Translate** into English
- **Ask** your own question (X/Twitter profile links start with "Who is this person?")

The link appears on its own line, without the page title or other text the sending app adds. Choose **New chat** (default) or **Last chat** before you tap Send. Images, video and audio are not share targets. Shared files are copied to `Documents/Verdroid/Input/`, which needs All files access.

> 💡 **Tip:** allow **Display over other apps** (Settings → Chat heads → Permissions). The share card then opens over the app you're in and collapses into the chat head bubble after Send, and the reply shows up there with an unread badge. Without the permission, shares open the full Verdroid app.

<p>
  <img src="docs/images/share-summarize.gif" width="280" alt="Sharing a news link to Verdroid and summarizing it">
  <img src="docs/images/share-x-profile.gif" width="280" alt="Sharing an X profile to Verdroid: Who is this person?">
</p>

## Quick start

### 1. Install the app

Download `Verdroid-v<version>.apk` from [Releases](https://github.com/chr243/Verdroid/releases) (or add the repo to [Obtainium](https://github.com/ImranR98/Obtainium)) and open it on the phone (Android 11+, minSdk 30), or with adb:

```bash
adb install -r Verdroid-v<version>.apk
```

Releases since v1.0.40 are R8-optimized and signed with a release key. Coming from an older `-debug` build, uninstall it once first (different signature). The in-app GitHub update check is **off by default** (Settings → App → *Check for updates on GitHub*); when on, it downloads APKs from this repo's releases and installs only after you confirm.

Then open Verdroid → **Settings → LLM providers**. A ✓ on a chip means that provider is ready. Tap **OpenRouter** to add keys ("Get a free key" opens openrouter.ai/keys), use **Kilo** with no key, or pick another provider, then Save, Test connection and add its models.

### Shared folder (optional, for `workspace_*`)

Open **Settings → Tools → Shared folder** and tap **Grant** to give Verdroid *All files access* (Android 11+ `MANAGE_EXTERNAL_STORAGE`). Verdroid then creates `Documents/Verdroid/Input` and `Documents/Verdroid/Output`.

### 2. Termux (optional, for `termux_run`, `selenium_*`, `termux_python`, `ebook_translate`)

Open Verdroid → **Settings → Tools → Termux** and tap **Set up Termux**. It walks through everything and continues each time you come back to Verdroid: opens F-Droid if [Termux](https://f-droid.org/packages/com.termux/) is missing, asks for the *Run commands in Termux* permission, copies the `allow-external-apps` command and opens Termux for one paste (Termux refuses outside commands until that is set), then starts `termux-setup-storage` in Termux so you only tap Allow. Packages under **Available to install** are installed in the background with `pkg`.

### 3. Shizuku (optional, for `run_shell`)

Install [Shizuku](https://shizuku.rikka.app/), open it and start it via **Wireless debugging** (Developer options → Wireless debugging → pair from the Shizuku app), or from a computer:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Then open Verdroid → **Settings → Shizuku & Git** and grant the Shizuku permission; **Test (id)** should report `uid=2000(shell)`. For `rish_run`, add rish there too (see *rish* above). All grants are also listed under **Settings → Phone → Permissions**.

### 4. Build from source

Requires JDK 17 and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/chr243/Verdroid.git && cd Verdroid
echo "sdk.dir=$ANDROID_HOME" > local.properties   # never commit this file
./gradlew assembleDebug testDebugUnitTest lint
# APK: app/build/outputs/apk/debug/Verdroid-v<version>-debug.apk
# Release (minified; debug-key signed unless you configure VERDROID_KEYSTORE_PROPS): ./gradlew assembleRelease
```

## Permissions

Everything beyond Internet is optional and only used for the feature that needs it (Settings → Phone → Permissions shows status and a Grant button for each):

| Permission | Why | When |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to OpenRouter/Kilo, web search/fetch, Git, optional update check | Always (HTTPS only; cleartext only to `localhost`) |
| Shizuku / rish | `run_shell` / `rish_run` as the adb shell user | Only if Shizuku is installed and you grant it |
| `SYSTEM_ALERT_WINDOW` (overlay) | Chat heads / floating chat window in Overlay mode | Only if you pick Overlay chat heads |
| `POST_NOTIFICATIONS` | Task progress, completions, Bubbles | Android 13+, asked when needed |
| `com.termux.permission.RUN_COMMAND` | Run bash/Python in Termux (`termux_*`, PDF, ebook tools) | Only if Termux is installed and you allow it |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | The shared `Documents/Verdroid` folder (Input/Output) | Optional; workspace tools stay inside that folder |
| `REQUEST_INSTALL_PACKAGES` | Install an update downloaded by the opt-in GitHub updater | Only when you tap Install |
| `FOREGROUND_SERVICE*`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep a running task and chat heads alive in the background | Only while a task / chat head runs |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Optional `screen_*` tools read and tap other apps' UI | Only if you enable it in Android settings; used only when a task asks for it |

No analytics, no ads, no Verdroid backend. Termux packages and Python modules are installed only after you agree in chat.

## Documentation

- [AGENTS.md](AGENTS.md): repo map, commands and rules for AI coding agents and contributors
- [CONTRIBUTING.md](CONTRIBUTING.md): how to contribute
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, tools and version history

## Status and limitations

Personal project; release builds are signed with the project's own key. There is no in-app browser and no Facebook tools. Pages that need JavaScript can be read with the optional headless Chromium + Selenium add-on in Termux; pages behind a login are not supported, apart from opt-in betas (off by default in Settings → Tools): cookie tools in Web that reuse cookies from an in-app login or a cookie export file, and a Twitter group that reads your X timeline, profiles and search and posts only after you confirm. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#known-limitations).

## License

Verdroid is licensed under the GNU General Public License v3.0 (`GPL-3.0-only`). See [LICENSE](LICENSE) for the full text.

## Support

If you find Verdroid useful, you can [buy me a coffee](https://www.buymeacoffee.com/chr243).
