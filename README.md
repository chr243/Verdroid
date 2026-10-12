# Verdroid

[![Get it on Obtainium](docs/images/badge_obtainium.png)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.verdroid.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fchr243%2FVerdroid%22%2C%22author%22%3A%22chr243%22%2C%22name%22%3A%22Verdroid%22%7D)
[![GitHub release](https://img.shields.io/github/v/release/chr243/Verdroid?label=GitHub%20release)](https://github.com/chr243/Verdroid/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Also listed in [ARK-Store](https://github.com/topics/arkstore) (GitHub topic `arkstore`).

**An on-device AI agent for Android that actually does things:** it searches the web and fetches pages and APIs over HTTP, runs bash and Python in Termux (optionally with headless Chromium + Selenium), runs shell commands through Shizuku or rish, drives apps with Accessibility, translates ebooks, works with files in `Documents/Verdroid` and Git, and reads crypto market data (work in progress), all tuned to work well on **free OpenRouter / Kilo models**, with automatic model and key fallback and rate-limit recovery. No server and no cloud backend: your keys stay encrypted on your phone.

| Chats | Chat thread |
|:---:|:---:|
| ![Verdroid chat list](docs/images/chat-list.jpg) | ![Verdroid chat thread running ebook_translate](docs/images/chat-thread.jpg) |

## Features

- **Messenger-style chat UI** (Kotlin, Jetpack Compose, Material 3) with chat heads / Android Bubbles and a quota indicator. Chat avatars are circles in shades of the theme green (lighter/darker per chat, theme-aware light/dark). Tool calls show as expandable cards; consecutive shell calls (`termux_run`, `termux_python`, `rish_run`, `run_shell`) stack into one expandable "termux_run ×N" row.
- **Free-model agent loop:** tool calling on OpenRouter free models and Kilo, priority list, automatic fallback across models and API keys, backoff and auto-resume after rate limits.
- **Any OpenAI-compatible API:** Settings → LLM providers (OpenRouter keys, Kilo, and presets for OpenAI, Groq, DeepSeek, Together, Ollama and LM Studio, plus *Custom*: base URL, encrypted API key, optional extra headers, *Fetch models* via `GET /models` or a manual model id). Custom models join Model priority as `model (Custom)`. `http://` is allowed only for `localhost`/`*.local` (LAN IPs need https or a `*.local` name).
- **Web search (default):** `web_search`, a keyless multi-engine search ported from [hec-ovi/websearch-skill](https://github.com/hec-ovi/websearch-skill) (MIT): DuckDuckGo (html, with lite fallback), Brave, Bing, Mojeek, Yahoo and Wikipedia queried in parallel over plain HTTP, redirect links unwrapped (`duckduckgo.com/l/?uddg=`, Bing `ck/a`, Yahoo `/RU=`), URLs canonicalised and deduplicated, then ranked with de-correlated reciprocal-rank fusion. No browser, so it is fast and avoids most captchas; a blocked engine just drops out.
- **Web fetching:** `web_fetch`, a plain HTTP GET/HEAD (no browser, no JavaScript) for a known URL or API. `format=markdown` returns a clean Markdown extract of the page, paginated by tokens (`page`, `page_size_tokens`, cached), wrapped in an untrusted-content fence, with block/captcha detection.
- **Crypto (work in progress):** Coinbase Exchange market data, a local backtest and optional live trading (`crypto_place_order` / `crypto_cancel_order` are off by default; the API key is stored only in EncryptedSharedPreferences). ⚠️ All crypto/Coinbase features are experimental and on the todo list, not a finished product: expect rough edges and changes, and don't rely on them with real funds.
- **Device control:** `run_shell` via Shizuku (shell uid), `screen_*` tools via an Accessibility service, and `termux_run` (bash in Termux through its RUN_COMMAND service, cwd `~/verdroid-work`) with optional Termux packages (ffmpeg, imagemagick, yt-dlp, jq, curl, pandoc, …) installable from Settings → Tools → Available to install. `termux_run` returns as soon as the command finishes; `timeout_seconds` is only an upper limit (background jobs left running are stopped).
- **Headless Chromium + Selenium (in Termux):** the optional `chromium-selenium` add-on (Settings → Tools → Available to install: `x11-repo`/`tur-repo` Chromium + chromedriver, `pip install selenium`) powers `selenium_open` (title, visible text, links), `selenium_page_source` (rendered HTML) and `selenium_screenshot` (PNG), all headless, plus `termux_python` to run Python scrapers the agent writes. Scripts stay in the private app workspace (e.g. `scrapers/*.py`); scraped files go to `Documents/Verdroid/Output` (run `termux-setup-storage` in Termux once). Use it for JavaScript-heavy pages; `web_search`/`web_fetch` stay the default.
- **Shared folder:** `/storage/emulated/0/Documents/Verdroid` with `Input/` (files you give Verdroid) and `Output/` (its results: translations, screenshots, scripts, projects), created at launch once *All files access* is granted (Settings → Tools → Shared folder, or Settings → Phone → Permissions). `workspace_list` / `workspace_read` / `workspace_write` / `workspace_delete` can only reach that tree (path escapes and symlinks are rejected).
- **Tool presets per chat:** the chat ⋮ menu has **Tool presets** (All / Web / Files / Termux / Device), all on by default, to limit what the agent may use in that chat.
- **AGENTS.md:** Settings → AGENTS.md holds your standing instructions for the agent; they're added to every chat.
- **Skills:** the agent can save reusable multi-step procedures it worked out with you (`skill_save`, `skill_edit`, `skill_delete`, `skill_list`, `skill_get`) as `files/skills/<id>/SKILL.md` inside the app. Settings → Skills lists them with an on/off switch and Delete; the system prompt only gets an index of enabled skills (name + one-line description, required on save), and the agent loads the full SKILL.md with `skill_get` when a task matches. The agent offers to save a skill when a procedure looks reusable.
- **rish:** in Settings → Shizuku & Git → rish, tap **Find rish** (with All files access it scans Download, Documents and Documents/Verdroid/Input) or **Pick rish file** for the `rish` exported by Shizuku (*Use Shizuku in terminal apps* → Export files; see [Shizuku-API/rish](https://github.com/RikkaApps/Shizuku-API/tree/master/rish)). Verdroid stages `rish` + `rish_shizuku.dex` in its private storage, then copies both to `/data/local/tmp/verdroid_rish/` through Shizuku and `chmod +x` both (**Fix rish permissions** repeats that; needs Shizuku running). The agent runs commands with `rish_run` from that folder with `RISH_APPLICATION_ID=com.termux`. Deliverables (including screenshots) go to `Documents/Verdroid/Output`.
- **Ebook translation:** `ebook_translate` (Termux Python: MOBI first, then EPUB/PDF/DOCX/TXT, plain-text fallback if a reader fails). `googletrans>=4.0.2` with a browser User-Agent, chunks of at most 4000 characters (never 5000+), ~0.3 s between requests with backoff on *Too many requests*, a 5–10 s pause every 4 chapters, MyMemory fallback, per-engine language-code handling, and resume after interruptions. It is two-step: the first call estimates chapters, chunks and time and the agent asks you to confirm before translating. Missing Python packages are installed on first use only after you agree in chat (the **ebook-translate** add-on under Settings → Tools installs them up front). Attach a file in chat or put it in `Documents/Verdroid/Input/`; the result lands in `Output/`.
- **Attach file in chat:** the composer’s **+** picks a file (SAF) and copies it into `Documents/Verdroid/Input`. The chat shows a clean 📎 chip with the file name and size; the path and tool hints are sent to the model only. A chat started with just an attachment is titled after the file.
- **Permissions in one place:** Settings → Phone → Permissions lists everything Verdroid can ask for (All files access, notifications, Termux RUN_COMMAND, Shizuku, accessibility, overlay, battery optimisation, installing updates) with status and a Grant button, plus a shortcut to *Set up Termux*.
- **Git:** clone / status / commit / push with JGit inside the app workspace.
- **Memory, MCP client, sandboxed file tools** and a keep-alive service that only runs while a task runs.
- **Security first:** API keys in EncryptedSharedPreferences (Android Keystore), no backups, HTTPS only.

## Quick start

### 1. Install the app

Download `Verdroid-v<version>.apk` from [Releases](https://github.com/chr243/Verdroid/releases) (or add the repo to [Obtainium](https://github.com/ImranR98/Obtainium)) and open it on the phone (Android 11+, minSdk 30), or with adb:

```bash
adb install -r Verdroid-v<version>.apk
```

Releases since v1.0.40 are R8-optimized and signed with a release key. Coming from an older `-debug` build, uninstall it once first (different signature). The in-app GitHub update check is **off by default** (Settings → App → *Check for updates on GitHub*); when on, it downloads APKs from this repo's releases and installs only after you confirm.

Then open Verdroid → **Settings → LLM providers**: tap **OpenRouter** to add one or more keys (`sk-or-…`, "Get key" links to openrouter.ai/keys), use **Kilo** free models without a key, or set up OpenAI, Groq, DeepSeek, Together, Ollama, LM Studio or any OpenAI-compatible API.

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
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | `screen_*` tools read and tap other apps' UI | Only if you enable it in Android settings; used only when a task asks for it |
| Shizuku / rish | `run_shell` / `rish_run` as the adb shell user | Only if Shizuku is installed and you grant it |
| `SYSTEM_ALERT_WINDOW` (overlay) | Chat heads / floating chat window in Overlay mode | Only if you pick Overlay chat heads |
| `POST_NOTIFICATIONS` | Task progress, completions, Bubbles | Android 13+, asked when needed |
| `com.termux.permission.RUN_COMMAND` | Run bash/Python in Termux (`termux_*`, PDF, ebook tools) | Only if Termux is installed and you allow it |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | The shared `Documents/Verdroid` folder (Input/Output) | Optional; workspace tools stay inside that folder |
| `REQUEST_INSTALL_PACKAGES` | Install an update downloaded by the opt-in GitHub updater | Only when you tap Install |
| `FOREGROUND_SERVICE*`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep a running task and chat heads alive in the background | Only while a task / chat head runs |

No analytics, no ads, no Verdroid backend. Termux packages and Python modules are installed only after you agree in chat.

## Documentation

- [AGENTS.md](AGENTS.md): repo map, commands and rules for AI coding agents and contributors
- [CONTRIBUTING.md](CONTRIBUTING.md): how to contribute
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): architecture, tools and version history

## Status and limitations

Personal project; release builds are signed with the project's own key. There is no in-app browser and no X/Facebook tools (removed). Pages that need JavaScript can be read with the optional headless Chromium + Selenium add-on in Termux; pages behind a login are not supported. All crypto/Coinbase features (market data, backtest, trading) are work in progress and still on the todo list. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#known-limitations).

## License

Verdroid is licensed under the GNU General Public License v3.0 (`GPL-3.0-only`). See [LICENSE](LICENSE) for the full text.
