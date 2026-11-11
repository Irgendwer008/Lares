# Requirements

Agreed with the product owner. Details of the "how" are on the linked pages.

## Rules
1. The user configures **apps** and **websites** (domains, optionally paths, e.g.
   `youtube.com/shorts`) to block.
2. A rule is **always blocked** or has a **daily time limit** (e.g. 30 min/day). Time schedules
   and open-count limits are not part of v1.
3. Daily limits restart at a **configurable time** (default 00:00). A change takes effect after
   the next reset.
4. **Templates** (YouTube, Instagram, TikTok, Snapchat, Facebook, X, Reddit, LinkedIn, Threads,
   Pinterest, Twitch, Tumblr, Bluesky) add the app(s), known alternative clients and the
   website(s) in one step – optionally as a group.
5. **Groups** combine apps and websites under one limit: always blocked, or X minutes per day
   **shared** (foreground time of all members is summed). The group switch turns all members on
   or off. Members use the group's limit and switch. A target can be a single rule **and** a group
   member at the same time: both limits apply, whichever is reached first blocks.

## Tighten vs. loosen
6. **Tightening** (adding rules / members, lowering limits, switching things on, adding
   notification channels) never needs the code.
7. **Loosening** (removing or disabling rules, raising limits, switching protections or website
   blocking off, changing the blocking mode, the reset time, the guardian code or notification
   channels) needs the guardian code. Full table: [Unlock & Security](unlock-and-security.md).

## Guardian code
8. The guardian chooses a **static password** or **TOTP** (RFC 6238, any authenticator app) at
   setup; changing it later needs the code.
9. A correct code puts the app into **unlocked mode** for 10 minutes: every change is allowed.
   It ends with **"Lock now"**, when the timer runs out, or when the phone restarts.
10. While unlocked, a **pause** (all blocking suspended; presets + custom, no maximum) can be
    started. It keeps running after locking and ends automatically (or early, without code, or
    when the phone restarts).
11. Unlocking and pauses are **not** reported to the guardian; only tamper events are.
12. A **recovery code** is generated at setup and shown once, for the guardian to keep. It can be
    copied or **shared** (text + file `Recovery-Codes-Lares.txt`); nothing is stored on the phone.
    Using it unlocks and forces setting up a new code.
13. The guardian code is never offered to password managers (autofill disabled app-wide).

## Blocking
14. The app blocking mode is chosen in the settings (switching it later is loosening):
    **Accessibility service** (recommended) or **Usage access + overlay**.
15. **Website blocking** reads the URL bar of supported browsers via the Accessibility service;
    unsupported browsers are blocked completely while website blocking is on. No VPN / DNS
    blocking – the user's own VPN must stay usable.
16. Website blocking and the settings guard **always need the Accessibility service**, also in
    Usage access mode (then both run).
17. The main screen lists everything blocking still needs at once (mode, permissions, device
    administrator – while no mode is chosen, including what the recommended Accessibility mode
    needs) and recommends the battery-optimization exemption.

## Tamper protection (each toggleable)
18. Prevent uninstalling (device administrator).
19. Guard Lares' own sensitive system-settings pages (needs the Accessibility service).
20. Restart after reboot / app update; record protection gaps.
21. Notify the guardian on tamper events.

## Guardian notifications
22. Channels: **Telegram bot**, **ntfy**, **email (SMTP)**, **webhook**, **Matrix**; each with its
    own message language and a test message. See [Notifications](notifications.md).

## Backup
23. **Export** rules and groups as a JSON file (no codes, tokens or usage data). **Import adds
    only** (never removes or loosens), so it needs no code.

## Settings
24. Settings screen (menu icon on the main screen), split into **App settings** – guardian code,
    blocking mode, website blocking, reset time, protections, notification channels, backup,
    language – and **Info** – about, privacy policy, license, open-source libraries.

## Platform
25. Distributed via **Google Play and F-Droid** → FOSS-only dependencies, no Google Play Services
    (see [Distribution](distribution.md)). License **GPL-3.0-or-later**.
26. Kotlin + Jetpack Compose, **minSdk 26**.
27. UI in **English and German**, docs in English; adding languages must be easy
    (see [Localization](localization.md)).
