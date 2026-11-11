# Architecture

> Package / application id: `de.lares_app`. Build: AGP 9.5 (built-in Kotlin), Kotlin 2.4,
> KSP, Gradle version catalog in `gradle/libs.versions.toml`. License header
> `SPDX-License-Identifier: GPL-3.0-or-later` in every source file.

## Stack
- Kotlin, Jetpack Compose (Material 3), minSdk 26, compile/targetSdk 37, JVM target 17
- Hilt (DI), Room (rules, usage, tamper events), typed DataStore (settings + unlock state as one JSON file)
- WorkManager (alert delivery queue, daily resets), AlarmManager (pause / session expiry)
- OkHttp for notification channels, kotlinx.serialization for JSON (export, channel configs)
- Android Keystore for secrets (TOTP secret, channel tokens)

## Main components
| Component | Responsibility |
|---|---|
| `RuleRepository` | CRUD for rules; enforces tighten-vs-loosen policy |
| `PolicyEngine` | Pure Kotlin: given (foreground package / URL, time, usage, pause state) → allow / block. Unit-testable, no Android deps |
| `LaresAccessibilityService` | Foreground app detection (Accessibility mode), URL bar reading, settings-page guard |
| `UsageMonitorService` | Foreground service polling `UsageStatsManager` (Usage Stats mode) |
| `BlockScreen` | Full-screen activity / overlay shown when something is blocked |
| `UsageTracker` | Accumulates foreground time per rule for daily limits |
| `UnlockManager` | Verifies password / TOTP / recovery code, rate limiting, pause & edit sessions |
| `LaresDeviceAdminReceiver` | Uninstall protection, deactivation alert |
| `BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` → restart monitoring |
| `TamperDetector` | Detects gaps, clock changes, disabled services → events |
| `AlertDispatcher` | Queues tamper events, sends through configured `AlertChannel`s |

## Data model (initial)
- `Rule(id, type = APP|WEBSITE, target, limitMinutesPerDay?, enabled, createdAt)`
- `UsageRecord(ruleId, date, foregroundMillis)`
- `TamperEvent(id, type, timestamp, details, delivered)`
- `AlertChannelConfig(id, type, configJson[encrypted], language)`
- Settings: blockingMode, websiteBlockingEnabled, protections toggles, unlockMethod,
  credential hash/secret, recovery hash, pause/session end times, dailyResetTime
  (+ pending new reset time until the next reset).
- `BackupManager`: JSON export/import (no secrets), import diffed via `RuleRepository` policy.

## Module layout
Single `app` module. Packages:
- `core/policy`, `core/unlock`, `core/data`, `core/time` – pure Kotlin, no Android imports, unit-tested
  with in-memory fakes (`app/src/test/.../testing/Fakes.kt`).
- `platform` – Android implementations (Room, DataStore, Keystore, clock, app catalog).
- `di` – Hilt wiring (`AppModule`), the only place that connects core and platform.
- `ui` – Compose screens + ViewModels.
- Later: `blocking`, `tamper`, `alerts`.

## Implemented so far
`core/policy` (pure Kotlin, unit-tested in `app/src/test`):
- `Rule`, `RuleKey` – rule model; key = type + normalized target (used for import matching).
- `UrlMatcher` – URL-bar parsing (scheme/`www.`/port/query stripping, IDN), subdomain and
  case-insensitive path-segment matching.
- `PolicyEngine` – (package, URL, `PolicySnapshot`) → `Decision.Allow(counted rules,
  remaining)` / `Decision.Block(reason)`. Order: pause → exempt → always-on → unsupported
  browser → limits.
- `ChangePolicy` – tighten / loosen classification for rules, rule sets (import), toggles,
  reset time.
- `ResetSchedule` / `UsagePeriod` – configurable reset; pending change applies after the next
  reset; `advance()` is monotonic (clock set back / time-zone change never grants a new quota).
- `TimedSession` – pause / edit-session end via `elapsedRealtime` (same boot) or wall clock.

`core/unlock`:
- `PasswordHasher` – PBKDF2-HMAC-SHA256, self-describing `pbkdf2-sha256$iter$salt$hash` format.
- `Totp` (+ `Base32`) – RFC 6238, ±1 step, replay protection via last used counter, `otpauth://` URI.
- `RecoveryCode` – 20 chars Crockford Base32 (100 bits), normalized input, stored as hash.
- `AttemptPolicy` – 4 free attempts, then 1 min doubling up to 24 h; tamper event at 5, 10, 15 …
- `UnlockManager` – setup (password / TOTP with confirmation), verify, one-time `UnlockGrant`
  (5 min) required to start pause / edit session. Recovery code forces re-setup.

`core/data`:
- `AppState` (`AppSettings` + `UnlockState`) persisted via `StateStore`; `SecretCipher` (Keystore).
- `EditSessionGate` – the single check "may this loosening change happen now?"
  (open before setup, otherwise only during an edit session).
- `RuleRepository`, `SettingsRepository` – every change classified via `ChangePolicy`;
  loosening without edit session → `CodeRequiredException` (UI offers code entry).
- `TamperLog` – tamper events for the later alert dispatcher.

`ui`: setup flow (intro → method → password / TOTP QR → recovery code), home (status card,
rule list, rule dialog), add rule (installed apps / website, limit), unlock dialog
(code → pause or edit session, presets + custom duration).

Room schema JSON is exported to `app/schemas/` (commit it; needed for future migrations).
