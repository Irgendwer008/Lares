# Architecture

Package / application id `de.lares_app`. Build: AGP 9.5 (built-in Kotlin), Kotlin 2.4, KSP,
version catalog `gradle/libs.versions.toml`. Every source file carries
`SPDX-License-Identifier: GPL-3.0-or-later`.

## Stack
- Kotlin, Jetpack Compose (Material 3), minSdk 26, compile/targetSdk 37, JVM target 17.
- Hilt (DI), Room (rules, groups, usage, tamper events), typed DataStore (settings + unlock state
  as one JSON file), kotlinx.serialization (state, backup, network payloads).
- Android Keystore (AES-GCM) for secrets: TOTP secret, channel tokens / passwords.
- Networking without extra libraries: `HttpsURLConnection` and an own minimal SMTP client.
- ZXing (QR code), AboutLibraries (license screen).

## Packages
Single `app` module:
- `core/policy`, `core/unlock`, `core/data`, `core/blocking`
- `UsageCounter` / `UsageSnapshot` – foreground time per rule / group in the current period (a
  group counts once per tick).
- `LimitWarnings` – 5 / 1 minute warnings, once per threshold, subject and period.
- `ForegroundTracker` – foreground app from "activity resumed" events (Usage access mode).

`core/alerts`, `core/blocking`, `core/time` – pure Kotlin (no Android
  imports), unit-tested with in-memory fakes (`app/src/test/.../testing/Fakes.kt`).
- `platform` – Android implementations (Room, DataStore, Keystore, clock, app catalog,
  permission checks, network senders, recovery-code sharing).
- `blocking` – Android components for blocking (accessibility service, blocking controller, block
  screen, countdown overlay, device admin receiver).
- `di` – Hilt wiring (`AppModule`), the only place that connects core and platform.
- `ui` – Compose screens and ViewModels.

## Data model
- `Rule(id, type = APP|WEBSITE, target, limitMinutesPerDay?, enabled, groupId?, createdAt)`
- `RuleGroup(id, name, limitMinutesPerDay?, enabled)` – members are enforced with the group's
  limit and switch (`Rule.effective()`).
- `UsageEntity(ruleId, periodStart, foregroundMillis)`, `GroupUsageEntity(groupId, periodStart,
  foregroundMillis)` – keyed by usage period start (see `ResetSchedule`).
- `TamperEvent(id, type, timestamp, details, delivered)`
- `AppState` (DataStore): `AppSettings` (setup complete, blocking mode, website blocking,
  protections, reset time + pending change, `AlertChannelConfig` list, countdown / warning
  switches), `TrackedPeriod` (the usage period currently counted) and `UnlockState` (method,
  password hash / encrypted TOTP secret, last TOTP counter, recovery hash, re-setup flag, failed
  attempts, lockout, pause, unlocked session).
- `AlertChannelConfig(id, type, language, server, address, port, startTls, username, sender,
  secretEncrypted)` – field use per type documented in the class.

Room stays at version 1 and is changed in place until the first release (no migrations, no schema
export). After a schema change, uninstall the app or clear its data. Before the first release:
enable `exportSchema`, commit the schema, add migrations from then on.

## Implemented

`core/policy`
- `Rule`, `RuleKey`, `RuleGroup` – key = type + normalized target (duplicate detection).
- `UrlMatcher` – URL-bar parsing (scheme / `www.` / port / query stripping, IDN), subdomain and
  case-insensitive path-segment matching, `baseHost()` for display order.
- `PolicyEngine` – (package, URL, `PolicySnapshot`) → `Decision.Allow(counted rules / groups,
  remaining)` or `Decision.Block(reason)`. Order: pause → exempt → always-on → unsupported browser
  → limits.
- `ChangePolicy` – tighten / loosen classification for rules (also inside groups), groups,
  toggles, reset time.
- `ResetSchedule` / `UsagePeriod` – configurable reset; pending change applies after the next
  reset; `advance()` is monotonic (clock set back / time-zone change never grants a new quota).
- `TimedSession` – unlocked mode, pause, wrong-code waiting time on `elapsedRealtime`; on reboot
  either ends (`END`) or starts over (`RESTART`).
- `RuleTemplates` – templates incl. brand colors and display names of template apps.
- `SupportedBrowsers` – browser packages and their address-bar view ids.

`core/unlock`
- `PasswordHasher` – PBKDF2-HMAC-SHA256, `pbkdf2-sha256$iter$salt$hash` format.
- `Totp` (+ `Base32`) – RFC 6238, ±1 step, replay protection via the last used counter.
- `RecoveryCode` – 20 chars Crockford Base32 (100 bits), normalized input.
- `AttemptPolicy` – 3rd wrong code → 1 min waiting time, doubling up to 24 h.
- `UnlockManager` – setup (password / TOTP with confirmation, `completeSetup()` only after the
  recovery code was confirmed), `unlock()`, `lock()`, `startPause()`; slow hashing on
  `Dispatchers.Default`.

`core/data`
- `UnlockGate` – the single check "may this loosening change happen now?" (open before setup,
  otherwise only while unlocked).
- `RuleRepository`, `SettingsRepository` – every change classified via `ChangePolicy`;
  loosening while locked → `CodeRequiredException` (UI offers code entry). Backup export and
  add-only import (`Backup.kt`).
- `SetupRequirements` – which permissions / choices the current settings still need.
- `TamperLog` – tamper events for the alert dispatcher.

`core/alerts`
- `AlertChannelConfig` / `ChannelDraft` (validation), `AlertChannelRepository` (add free, edit /
  delete need the code), `AlertSender` interface.

`platform`
- `DataStoreStateStore`, `KeystoreSecretCipher`, `AndroidClock`, Room stores, `AppCatalog`
  (launchable apps via `<queries>`), `PermissionStatus` (accessibility, usage access, overlay,
  device admin, battery optimization + intents), `NetworkAlertSender` + `SmtpMailer`,
  `AlertMessages` (texts in the channel's language), `RecoveryExport` (clipboard, share sheet).

`blocking`
- `LaresAccessibilityService` – reports the foreground app (`TYPE_WINDOW_STATE_CHANGED` of
  activities only) and screen on / off; while website blocking is on, reads the address bar of
  supported browsers (nothing else of the window). Hosts the block screen and countdown.
  `BlockingLog` – diagnostics in debuggable builds only.
- `UsageMonitorService` – Usage access mode: foreground service polling `UsageStatsManager`
  (`ForegroundTracker`), hosts block screen and countdown (overlay permission).
  `UsageMonitorStarter` runs it while the mode is chosen. `SystemEvents` – screen / package
  broadcasts for both services.
- `BlockingController` – one-second loop on a single thread: `PolicyEngine` decision for the
  foreground app, time counting via `UsageRepository`, block screen (debounced), countdown overlay
  in the last 5 minutes, optional warnings. Services attach as hosts per mode; the host of the
  chosen mode supplies the foreground app and performs the effects. Active only when setup is
  complete; blocking stays active in unlocked mode.
- `BlockActivity` – full block screen (reason, reset time, "Close", "Go to Lares").
- `TimerOverlay` – countdown pill (accessibility overlay, or application overlay in Usage access
  mode; not touchable).
- `LaresDeviceAdminReceiver` – declared for "prevent uninstalling"; reporting follows.

`platform` (blocking)
- `UsageRepository` – counts in memory, writes to Room at most every 15 s and on period change,
  advances the tracked period via `ResetSchedule.advance()`, drops older periods.
- `InstalledBrowsers` – apps with a generic web-link filter (blocked if unsupported).
- `ExemptApps` – Lares, launchers, dialers, keyboards, system UI, Settings, permission / install
  dialogs (never blocked, hidden when adding rules).
- `LimitNotifier` – warning notifications; permission requested only when a warning is switched
  on, returned via `revokeSelfPermissionOnKill` when all are off.

`ui`
- Setup flow (`SetupMode`: FIRST with intro, RECOVERY after the recovery code, CHANGE from the
  settings), main screen (setup issues, status / unlocked / pause cards, groups, single rules),
  add screen (templates, group, apps, website), unlock and pause dialogs, settings (app settings /
  info), notification channel editor, privacy policy, license, open-source libraries.

## Planned (rest of the blocking milestone)
| Component | Responsibility |
|---|---|
| Settings guard (D) | Blocking Lares' sensitive system-settings pages |
| `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` → restart monitoring |
| `TamperDetector` | Detects gaps, clock changes, disabled services → tamper events |
| `AlertDispatcher` | Sends tamper events through the configured channels (WorkManager, retries) |
