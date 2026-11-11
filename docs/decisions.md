# Decision Log

| # | Date | Decision | Reason / alternatives |
|---|---|---|---|
| 1 | 2026-11-10 | Unlock method selectable at setup: password or TOTP | Password is simple; TOTP allows remote, non-reusable codes |
| 2 | 2026-11-10 | App-blocking mode selectable: Accessibility or Usage Stats + Overlay | Trade-off reliability vs. sensitive permission; Device Owner rejected (needs reset/adb) |
| 3 | 2026-11-10 | **No VPN/DNS blocking** | User's own VPN must always be usable; Private DNS gets overridden by VPNs anyway |
| 4 | 2026-11-10 | Website blocking = URL-bar reading + block unsupported browsers | Only option working on stock devices without VPN/root/Device Owner |
| 5 | 2026-11-10 | Website blocking always requires Accessibility Service | URL bar can only be read via Accessibility |
| 6 | 2026-11-10 | Tamper protections: uninstall block, settings guard, reboot restart, guardian alerts – each toggleable | User choice |
| 7 | 2026-11-10 | Code allows pause or edit session | Flexibility |
| 8 | 2026-11-10 | Tightening free, loosening requires code | Encourages stricter rules |
| 9 | 2026-11-10 | v1 rule types: always on, daily time limit | Schedules and open-limits deferred |
| 10 | 2026-11-10 | Alert channels: Telegram, ntfy, webhook, Matrix | SMS rejected (Play restrictions); Signal has no bot API |
| 11 | 2026-11-10 | Recovery code at setup | Avoid permanent lock-out |
| 12 | 2026-11-10 | Kotlin + Compose, minSdk 26 | ~95 % device coverage, modern stack |
| 13 | 2026-11-10 | UI EN + DE, docs EN, strict i18n rules | Easy extension to more languages |
| 14 | 2026-11-10 | Settings-page guard requires Accessibility Service in all modes | Reliable detection needs window content |
| 15 | 2026-11-10 | Distribute via Google Play **and** F-Droid | Reach + FOSS audience; implies FOSS-only deps, no Play Services, no `QUERY_ALL_PACKAGES` |
| 16 | 2026-11-11 | License: **GPL-3.0-or-later** | Keeps forks open source (no closed/tracking re-releases); F-Droid compatible; Apache-2.0 deps are compatible |
| 17 | 2026-11-11 | Pause / edit session: presets + custom, **no maximum duration** | Guardian decides the length when entering the code; sessions still end automatically |
| 18 | 2026-11-11 | **No guardian notification for pauses / edit sessions** (only tamper events) | Guardian entered the code, so they already know |
| 19 | 2026-11-11 | Daily reset time **configurable** (default 00:00); any change = loosening, effective after the next reset | Night owls; moving the reset in either direction can grant a fresh quota |
| 20 | 2026-11-11 | **Export / import of rules & settings in v1** (JSON, no secrets); import follows the tighten/loosen policy | Device change / reinstall without re-entering everything |
| 21 | 2026-11-11 | Android auto backup **and** device-to-device transfer disabled (`allowBackup=false` + `data_extraction_rules.xml` excluding everything) | Keystore-bound secrets cannot be restored elsewhere; rules move via the explicit export instead |
