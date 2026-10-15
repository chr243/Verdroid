# AGENTS.md — Verdroid

Verdroid (`com.verdroid.app`) is a native Android app (Kotlin, Jetpack Compose, Material 3, Hilt, Room) that runs a tool-using LLM agent on the phone itself. It talks to free OpenRouter and Kilo models with automatic model/key fallback and rate-limit recovery, and gives the agent real tools: keyless multi-engine web search (`web_search`, a port of hec-ovi/websearch-skill), plain HTTP fetching with a Markdown reader (`web_fetch`), crypto strategy backtesting only (`crypto_history_*`, `crypto_backtest`, `crypto_backtest_script`; no live or paper trading, results end with "Not financial advice"), shell commands via Shizuku, shell commands through rish (`rish_run`, from `/data/local/tmp/verdroid_rish`), bash in Termux (`termux_run`, via Termux's RUN_COMMAND service), headless Chromium + Selenium inside Termux (`selenium_*`, `termux_python` for agent-written scrapers), ebook/document translation in Termux Python (`ebook_translate`, estimate → user confirms → translate), PDF read/light edit in Termux Python (`pdf_*`: info, text extract with page markers, split/merge, annotate; PyMuPDF with pypdf fallback), JGit, sandboxed file tools, a shared `Documents/Verdroid` folder (`workspace_*`, All files access; chat **Attach file** copies into `Input/`), memory, agent-writable skills (`skill_*`, `files/skills/<id>/SKILL.md`, Settings → Skills), optional screen control via an Accessibility service, a Permissions screen and an MCP client. There is no in-app browser and no Facebook tools; X is only reachable through the opt-in Twitter group (headless Chromium with the user's own login, posting only after an in-app confirm). There is no backend server; everything runs on the device.

## Current state (v1.0.54, versionCode 83)

- **Twitter group** (opt-in, off by default, ToS warning dialog on every enable incl. per-chat force-on; `ToolGroups.needsTosWarning`): `x_timeline` / `x_user_posts` / `x_search` (`XTools.kt`; helper `xposts` extracts posts via `data-testid`, scrolls + dedupes; `XPosts.format`; results only returned to the agent, no files) and `x_post` (`XPostTool.kt`; helper `xpost`: one session, CDP `Input.insertText`, verify, single click, `posted=uncertain` never retried; `XPostConsent` Post / Cancel dialog in main app + bubble, "Post to X without asking" switch). Agent guidance: call X tools first; `cookie_login` only after NOT_LOGGED_IN.
- **Cookie tools in Web** (off by default): `cookie_login` (in-app WebView login `ui/cookies/CookieLogin.kt`, mobile Chrome UA, auto-close on login marker `LoginDetector`), `cookie_open`, `cookie_import`, `cookie_list`, `cookie_clear`. Cookies in EncryptedSharedPreferences (`CookieStore`), values redacted/scrubbed everywhere, injected via CDP `Network.setCookies` (add_cookie fallback). Legacy per-chat `COOKIES` preset → TWITTER (+ WEB when forced on).
- **Per-chat model pin** (chat ⋮ → Model; `ChatModelPins`, `ModelPins.resolve`, `PinnedOutcome`): pinned chat uses only that model (clear error, no fallback) unless *Allow fallback*; header shows 📌.
- **Overwrite consent:** `OverwritePolicy` / `OverwritePrefs` — "This chat" / "Always allow" for overwrite-only requests (`DestructiveDetector.overwriteOnly`); delete/move/rename always ask.
- **Crash log:** `CrashLog` UncaughtExceptionHandler → `Documents/Verdroid/Internal/crash/crash_*.txt` (app-private fallback), Settings → App → Crash log.

- **Share to Verdroid** (`ui/share/ShareActivity`; pure logic in `domain/share/ShareLogic` + `ShareDraft`, JVM-tested): ACTION_SEND / SEND_MULTIPLE for text, links and documents (never image/video/audio). Quick actions Summarize / Translate / Ask, one draft per action so typed text is never lost. Defaults: links → Summarize; X/Twitter profile links (`isXProfileUrl`) → Ask prefilled "Who is this person?"; text and files → Ask. `sharedText` reduces a shared link to the URL only (titles/labels dropped); the link is shown as a read-only line under the title, the Message field holds only the instruction, and `ShareDraft.outgoing` appends the link on Send. New chat (default) / Last chat (most recently updated chat, else new) via `AppPrefs.shareToLastChat`.
- **Bubble share by default:** with "Display over other apps" the sheet is a compact card on a translucent window (`Theme.Verdroid.Share`, `VerdroidTheme(transparentWindow = true)`) and after Send starts `ChatHeadService` (head + unread badge, reply in background). Without the permission it falls back to the full app (`ShareLogic.shareUsesBubble`). A share-spawned head (`chathead.ShareHead`) hides while `MainActivity` is in the foreground and comes back on leave only with an unread or running reply (`ShareLogic.restoreShareHead`). Self-test check: `share_target`.
- **Tool presets** (chat ⋮ menu) mirror the Settings → Tools groups, including Crypto backtesting; Charts and the self-test are always on and not shown in the dialog. Each group is a per-chat 3-state override (inherit / forced on / forced off, `ChatToolPresets`): effective = override if set, else Settings → Tools (`ToolPreset.effective`), so a chat can turn on a group that is off globally. Group descriptions live in `ToolGroups.ORDER` and are shared with Settings → Tools.
- **Default models:** `nvidia/nemotron-3-ultra-550b-a55b:free`, `openrouter/free`, `kilo:kilo-auto/free`; with no OpenRouter key OpenRouter entries are skipped without a request and Kilo is used.
- **Self-test `providers`:** a local/LAN provider server that is off is skipped (`ProviderProbe`), not failed.
- **Chat head:** no status dots on the head.
- **Share scope:** share messages with a link carry a hidden `[Share scope]` line (`ShareLogic.withScope`, hidden by `AttachmentText.stripScope`): stay on the shared site, `site:` search OK, other sites only suggested; X profiles may be searched generally.
- **Chat UI:** consecutive thinking/tool-only turns collapse into one "N steps" row (`ToolStacks.blocks`, `StepsCard`; spinner + current step / ✅ / ❌), in the main chat and the floating window; no status dots on tool cards. Self-test `chat_ui`.
- **Unread badge** counts only user-facing assistant replies (text, no tool calls): `TaskDao.observeConversations` + `UnreadPolicy`.

## Repo map

```
.
├── app/
│   ├── build.gradle.kts            app module (namespace/applicationId com.verdroid.app, versionCode/versionName)
│   ├── schemas/                    Room schema JSON exports (commit changes when the DB version changes)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── aidl/com/verdroid/app/        IShellService (Shizuku UserService)
│       │   ├── java/com/verdroid/app/
│       │   │   ├── VerdroidApp.kt, MainActivity.kt
│       │   │   ├── agent/        AgentLoop, AgentRunner, backoff, context/ (summarisation), tools/ (tool implementations, registry)
│       │   │   ├── chathead/     chat heads overlay + Android Bubbles
│       │   │   ├── data/         a11y, crypto (backtesting, keyless market data), ebook (translator script for Termux), git,
│       │   │   │                 termux (RUN_COMMAND manager, result receiver, Set up Termux flow, Selenium helper script),
│       │   │   │                 local (Room), mcp, memory, network (OpenRouter/Kilo clients, providers, model caps), notify,
│       │   │   │                 prefs, repository, secure (encrypted key store), settings (DataStore), skills (SkillStore),
│       │   │   │                 storage (Documents/Verdroid SharedFolder, ChatAttachment), tools (Termux add-ons, tool status),
│       │   │   │                 update, websearch (web_search engines + fusion), work (WorkManager)
│       │   │   ├── di/           Hilt AppModule (incl. tool registry)
│       │   │   ├── domain/       models, repository interfaces, use cases (no Android deps)
│       │   │   ├── keepalive/    foreground keep-alive service, boot receiver
│       │   │   ├── shizuku/      ShizukuManager, ShellExecutor, ShellUserService, RishStore (+ RishRunner for rish_run)
│       │   │   └── ui/           Compose screens + ViewModels (chats, chat incl. ToolStacks, menu, device, tools, permissions, skills, theme, …)
│       │   └── res/                        resources (launcher icon = leaf + circuit, adaptive mipmap PNGs on #133121)
│       └── test/java/com/verdroid/app/       JVM unit tests (JUnit 4), mirroring the main package layout
├── gradle/libs.versions.toml       version catalog (AGP 8.7.3, Kotlin 2.1.0, KSP, Hilt, Room, Compose BOM)
├── gradle/wrapper/                 Gradle 8.11.1 wrapper (always use ./gradlew)
├── docs/ARCHITECTURE.md            detailed architecture notes and version history
├── .github/                        CI workflow, issue and PR templates
└── AGENTS.md, README.md, CONTRIBUTING.md, METADATA.md
```

## Build, test, run

Toolchain: JDK 17, Android SDK with `platforms;android-35` and `build-tools;35.0.0` (compileSdk 35, targetSdk 35, minSdk 30).

```bash
# one-time: point Gradle at the SDK (local.properties is git-ignored, never commit it)
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug          # APK -> app/build/outputs/apk/debug/Verdroid-v<versionName>-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lint                   # Android lint; abortOnError = true, so any lint *error* fails the build (0 errors, ~50 warnings at v1.0.0; no baseline)
./gradlew assembleDebug testDebugUnitTest lint   # exactly what CI runs
./gradlew testDebugUnitTest --tests 'com.verdroid.app.data.crypto.*'   # a subset
```

Install and run on a device (USB or wireless debugging):

```bash
adb pair <phone-ip>:<pairing-port>     # Developer options → Wireless debugging → Pair with code
adb connect <phone-ip>:<port>
adb install -r app/build/outputs/apk/debug/Verdroid-v*-debug.apk
adb shell am start -n com.verdroid.app/.MainActivity
adb logcat --pid=$(adb shell pidof com.verdroid.app)
```

Shizuku (needed for `run_shell`): start Shizuku from its app via **Wireless debugging**, or over adb:

```bash
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

`run_shell` binds a Shizuku UserService / newProcess (`shizuku/ShellExecutor.kt`). `rish_run` instead uses the `rish` + `rish_shizuku.dex` exported by Shizuku (*Use Shizuku in terminal apps*): Settings → Shizuku & Git → rish → **Find rish** / **Pick rish file** stages them privately, then copies them through Shizuku to `/data/local/tmp/verdroid_rish/` with `chmod +x` (`shizuku/RishStore.kt`). Manual check over adb:

```bash
adb shell 'RISH_APPLICATION_ID=com.termux sh /data/local/tmp/verdroid_rish/rish -c id'   # expect uid=2000(shell)
```

## Coding conventions

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent. No formatter/linter plugin is configured besides Android lint (TODO(maintainer): decide whether to add ktlint/detekt).
- Package naming: everything lives under `com.verdroid.app.<layer>.<feature>` (`ui.*`, `data.*`, `domain.*`, `agent.*`, …). Tests use the same package as the class under test, in `app/src/test/java/com/verdroid/app/...`.
- DI with Hilt (`@Singleton`, `@Inject constructor`, bindings in `di/AppModule.kt`). New agent tools are registered in the tool registry there and listed in `AgentLoop` `TOOL_GROUPS`.
- JSON with kotlinx.serialization; HTTP with OkHttp/Retrofit; persistence with Room (bump the DB version and add a migration + exported schema in `app/schemas/`) or DataStore.
- `domain/` must stay free of Android dependencies.
- Version bumps: `versionCode` + `versionName` in `app/build.gradle.kts`.
- Add or update unit tests for every logic change; keep pure logic out of Android classes so it is JVM-testable.

## Security rules

- Never commit secrets: API keys (`sk-or-…`, Kilo keys), GitHub tokens, crypto exchange keys, `local.properties`, keystores (`*.jks`, `*.keystore`, `keystore.properties`) or `.env` files. They are git-ignored; do not force-add them.
- Never hard-code keys or tokens in source, tests or fixtures; use obviously fake values (`sk-or-test`).
- API keys live only in EncryptedSharedPreferences at runtime; keep them masked in UI and redacted in logs.
- Crypto is backtesting only: no live or paper trading tools, no exchange keys.
- No cleartext traffic (`res/xml/network_security_config.xml`).
- Keep `allowBackup=false` and the data-extraction exclusions.
- Release signing keys are not part of this repo (TODO(maintainer): document release signing if release builds are ever published).

## Verify before opening a PR

```bash
./gradlew assembleDebug testDebugUnitTest lint
git status --short          # no local.properties, keystores, APKs or build outputs staged
git diff --cached | grep -nEi 'sk-or-[a-z0-9]{8}|ghp_[A-Za-z0-9]{20}|BEGIN (RSA|EC|OPENSSH) PRIVATE KEY' && echo 'SECRET FOUND' || echo 'no secrets'
```

Termux tools wrap commands with `TermuxRunTool.capped(...)` (output to temp files, stdin `/dev/null`, leftovers killed) so they return as soon as the command exits; keep that wrapper when adding Termux-backed tools. `ebook_translate` chunks must stay below 5000 characters (tested).

Agent-initiated package installs (pip / apt / pkg / npm / gem / cargo) must go through `agent/tools/InstallConsent`: the tool returns `needs_install_confirmation` (packages, reason, estimate, `install_id`) and installs nothing; the agent asks the user and only re-calls with `confirm_install=true` + `install_id` after a yes (`false` = declined). Tools with a built-in setup run a probe first (`setupCommand(allowInstall = false)`). Settings → Tools add-ons the user taps Install on bypass the gate (the tap is the consent).

If you touched Shizuku, rish, Termux, Accessibility or chat heads, also test on a real device (these paths are not covered by JVM tests) and say so in the PR.

## Maintainer verification checklist (per release)

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint` passes locally and the CI run on `main` is green.
- [ ] `versionCode`/`versionName` bumped in `app/build.gradle.kts`; APK is named `Verdroid-v<versionName>-debug.apk`.
- [ ] If the Room schema changed: migration added and `app/schemas/` committed.
- [ ] Fresh install on a device: onboarding, add API key, send a chat, a tool call runs.
- [ ] `web_search` returns fused results, and `web_fetch format=markdown` returns a public page.
- [ ] Shizuku **Test (id)** returns `uid=2000(shell)`; rish **Test (id)** too if rish is set up.
- [ ] `termux_run` with `echo hi` returns within a few seconds (not after `timeout_seconds`).
- [ ] `ebook_translate` on a small file in `Input/` returns an estimate first, then translates into `Output/` after confirming.
- [ ] With a package missing, `termux_run` `pip install …` / `pdf_info` / `ebook_translate` ask before installing; "no" installs nothing, "yes" installs and continues.
- [ ] No secrets in the diff (see command above); `git ls-files | grep -E 'local.properties|\.jks|\.keystore'` is empty.
- [ ] GitHub release created with the APK attached; README, METADATA.md and docs/ARCHITECTURE.md are up to date.
