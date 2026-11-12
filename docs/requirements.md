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
   The first setup also ends in unlocked mode (first rules can be corrected without the code).
   It ends with **"Lock now"**, when the timer runs out, or when the phone restarts.
10. While unlocked, a **pause** (all blocking suspended; presets + custom, no maximum) can be
    started. It keeps running after locking and ends automatically (or early, without code, or
    when the phone restarts).
11. Unlocking and pauses are **not** reported to the guardian; only tamper events are.
12. A **recovery code** is generated at setup and shown once, for the guardian to keep. It can be
    copied or **shared** (text + file `Recovery-Codes-Lares.txt`); it is never stored in plain text
    on the phone (only a salted hash).
    Using it unlocks and forces setting up a new code.
13. The guardian code is never offered to password managers (autofill disabled app-wide).

## Blocking
14. The app blocking mode is chosen in the settings (switching it later is loosening):
    **Accessibility service** (recommended) or **Usage access + overlay**.
15. **Website blocking** (Accessibility mode only) reads the URL bar of supported browsers;
    unsupported browsers are blocked completely while website blocking is on. No VPN / DNS
    blocking – the user's own VPN must stay usable.
16. In Usage access mode, website blocking is not available (the switch is disabled with a hint
    to change the mode – if the Accessibility service is needed anyway, its mode is the better
    one). The settings guard always needs the Accessibility service, also in Usage access mode.
17. The main screen lists everything blocking still needs at once (mode, permissions, device
    administrator – while no mode is chosen, including what the recommended Accessibility mode
    needs) and recommends the battery-optimization exemption.
18. Opening a blocked app (or reaching its limit while using it) shows a **full block screen**
    immediately: what is blocked and why, time until the next reset, "Close" (home screen) and
    "Go to Lares". Blocking stays active in unlocked mode (only a pause suspends it).
19. **Limit countdown:** during the last 5 minutes of a limit, a small timer is shown in a corner
    over the app (can be switched off in the settings). Optional **notifications** 5 and/or 1
    minute before the limit ends; the notification permission is only requested when one of them
    is switched on, and returned when both are off again.
20. Essential apps can't be blocked: Lares, the home screen, phone / emergency calls, the
    keyboard, system UI and the Settings app as a whole (the settings guard covers its
    sensitive pages). They aren't offered when adding rules.
21. Time only counts while the app is in the foreground and the screen is on; not during a pause.
    The main screen shows the time used today per rule / group.

## Tamper protection (each toggleable)
22. Prevent uninstalling (device administrator).
23. Guard Lares' own sensitive system-settings pages (needs the Accessibility service).
24. Notify the guardian on tamper events (sent automatically, retried while offline).
25. Always: restart after reboot / app update; record tamper events while locked (permissions
    lost, protection gaps, boots without Lares, clock / time-zone changes, wrong codes, recovery
    code used).

## Guardian notifications
26. Channels: **Telegram bot**, **ntfy**, **email (SMTP)**, **webhook**, **Matrix**; each with its
    own message language and a test message. See [Notifications](notifications.md).

## Backup
27. **Export** rules and groups as a JSON file (no codes, tokens or usage data). **Import adds
    only** (never removes or loosens), so it needs no code.

## Settings
28. Settings screen (menu icon on the main screen), split into **App settings** – guardian code,
    blocking mode, website blocking, reset time, limit countdown and warnings, protections,
    notification channels, backup,
    colors (Lares colors or wallpaper colors, Android 12+; also asked on the first setup screen),
    language – and **Info** – about, privacy policy, license, open-source libraries.

## Platform
29. Distributed via **Google Play and F-Droid** → FOSS-only dependencies, no Google Play Services
    (see [Distribution](distribution.md)). License **GPL-3.0-or-later**.
30. Kotlin + Jetpack Compose, **minSdk 26**.
31. UI in **English and German**, docs in English; adding languages must be easy
    (see [Localization](localization.md)).
