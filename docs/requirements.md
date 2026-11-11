# Requirements

Status: agreed with product owner on 2026-11-10.

## Core
1. The user configures a list of **apps** and **websites** (domains, optionally paths) to block.
2. Rules apply **always on**, or with a **daily time limit** (e.g. 30 min/day for Instagram).
   Time schedules and open-count limits are *not* in v1.
   Daily limits reset at a **configurable time** (default 00:00). Changing it is loosening
   (either direction can grant a fresh quota) and takes effect after the next reset.
3. **Tightening** (adding rules, lowering limits, enabling protections) never needs a code.
4. **Loosening** (removing rules, raising limits, disabling protections, changing modes,
   changing unlock method or alert channels) requires the guardian code.

## Unlock
5. Unlock method is chosen at setup: **static password** or **TOTP** (RFC 6238, works with
   any authenticator app). Changing the method requires the current code.
6. A correct code offers a choice:
   - **Pause** – all blocking suspended for a chosen duration (presets + custom), ends automatically.
   - **Edit session** – rules can be loosened for a limited time.
   Both offer presets plus a custom duration with **no maximum**; both end automatically.
   Pauses and edit sessions are **not** reported to the guardian (only tamper events are).
7. At setup a **recovery code** is generated, shown once, to be stored by the guardian.

## Blocking mechanism
8. App blocking mode is chosen at first start and changeable in settings (loosening → code):
   - **Accessibility Service** mode
   - **Usage Stats + Overlay** mode
9. **Website blocking** reads the URL bar of supported browsers via the Accessibility Service.
   Unsupported browsers are blocked like apps while website blocking is enabled.
   No VPN / DNS based blocking – the user's own VPN must stay usable at all times.
10. Enabling website blocking **always requires the Accessibility Service**, also in Usage
    Stats mode (then both run).

## Tamper protection (each individually toggleable in settings)
11. Block uninstall (Device Admin).
12. Block the app's own sensitive system-settings pages. **Requires the Accessibility
    Service** (also in Usage Stats mode), like website blocking.
13. Auto-restart after reboot / app update; record gaps in protection.
14. Notify guardian on tamper events.

## Notifications to guardian
15. Pluggable channels, v1: **Telegram bot**, **ntfy**, **generic webhook**, **Matrix**.

## Backup
18. **Export / import** of rules and settings as a JSON file. Secrets (credential hash,
    TOTP secret, recovery hash, channel tokens) are never exported. Export is free.
    Import of changes that only tighten is free; if the import would loosen anything
    (remove rules, raise limits, disable protections …) it requires the code.

## Platform
15a. Distributed via **Google Play and F-Droid** → FOSS-only dependencies, no Google Play
    Services (see [Distribution](distribution.md)).
16. Kotlin + Jetpack Compose, **minSdk 26**. License **GPL-3.0-or-later**.
17. UI in **English and German**; docs in English. All text must be designed for easy
    addition of further languages (see [Localization](localization.md)).
