# Roadmap

Pre-release milestones, in order. Done milestones are summarized in
[Architecture](architecture.md) ("Implemented").

## 1. Blocking
- **A – App blocking (Accessibility mode)** – done: foreground detection, block screen, daily
  limits incl. groups, countdown overlay, optional warnings, exempt apps.
- **B – Website blocking** – done (address-bar ids still to be verified on devices): URL-bar
  reading in supported browsers, blocking unsupported browsers.
- **C – Usage access mode** – done: foreground service polling `UsageStatsManager`, block screen
  and countdown via the overlay permission, service notification.
- **D – Protection & notifications** – done (settings-page detection still to be verified on
  devices): restart after reboot / update, settings guard, tamper detection (permissions lost,
  clock / time zone changed, gaps, boots without Lares), uninstall-protection reporting,
  automatic sending to the guardian channels with retries.

## 2. Design tweaks – done
- App icon: adaptive icon (parchment background, clay-orange "L" from `docs/Logo.svg`, inside the
  66 dp safe circle; also the themed monochrome icon). Wordmark (`docs/Logo_long.svg`) in the main
  screen title, on the setup intro and in the About dialog.
- Countdown pill with a small Lares "L" next to the timer.
- Lares theme (clay orange with olive and teal accents on parchment, logo brown) or wallpaper
  colors (Material You, Android 12+) – chosen on their own first setup screen with two previews,
  changeable in the settings.
- Main screen: usage bars ("12 / 30 min"), empty state with the logo, clearly visible buttons in
  the cards. Icons for all settings entries; templates with installed apps first.
- Lares "L" on the block screen and as notification icon; the lock stays where it means "needs
  the code".

## 3. Release preparation & publishing
- Full review pass (bugs, security, leftovers, docs in sync with the code) – done.
- Versioning and release signing setup – done (see [Distribution](distribution.md)).
- GitHub Pages: privacy policy and the neutral page for blocked websites – done.
- Prepare everything needed for publishing on Google Play *and* F-Droid:
  - Google Play: store listing (EN / DE texts, screenshots, feature graphic from the wordmark),
    Accessibility API declaration (incl. video), special-use foreground service declaration,
    data safety form, privacy policy URL, check of the current device-admin policy.
  - F-Droid: `fastlane/metadata` (descriptions, screenshots), reproducible-build check – done;
    build recipe in `docs/fdroid/de.lares_app.yml`.
  - Done: store texts EN / DE in `fastlane/metadata/android/`, Play Console answers in
    [Play Console](play-console.md). Screenshots done. Open: device-admin policy check.
- Make the GitHub repository more user and contributor friendly – `README.md` and
  `CONTRIBUTING.md` done.
- On-device test of 1.0.0 – done (Motorola).
- Version 1.0.0 tagged (`v1.0.0`).
- Publish the app on Google Play – done (live).
- F-Droid – rejected for now (projects need more than three months of development history;
  reviewers saw no new features over similar apps).

## 4. Version 1.1
**Blocking gaps** (known limits from [Blocking](blocking.md))
- Picture-in-picture and split screen: also check the other visible windows – done
  (Accessibility mode).
- Block screen: "Available again at midnight" when the limits restart at 00:00 – done.
- Supported browser whose address bar can't be read (view id changed by an update): notice it
  (e.g. no URL for several seconds while website blocking is on) and treat the browser as
  unsupported, or at least show a setup issue – instead of silently allowing every website.
- Website rule paths: normalize repeated slashes and percent-encoding before matching
  (`youtube.com//shorts`, `%73horts`).

**Settings guard and protection**
- Test on other manufacturers (Samsung, Pixel, Xiaomi) and add their settings packages – e.g.
  Samsung's separate accessibility app (`com.samsung.accessibility`), Huawei / Honor's system
  manager.
- Android 13+ "active apps" panel in the quick settings can stop Usage access mode: detect it
  faster than the gap check, and point users to Accessibility mode.
- Test Usage access mode on a manufacturer with aggressive background limits.

**Policy details** (currently stricter than needed – no security impact)
- Switching from Usage access mode to Accessibility mode, narrowing a website rule
  (`youtube.com/shorts` → `youtube.com`) and cancelling a pending reset-time change could be free
  (they don't loosen anything).

**Smaller cleanups**
- Avoid the blocking main-thread reads of the settings (initial theme, device-admin disable
  dialog) – e.g. a small cache.
- Merge duplicate strings with identical text; a proper plural for "groups" in the import result.

**Ideas – to be decided**
- Schedules (blocked at certain times of day) and open-count limits (not part of v1).
- A protection log in the app (the tamper events Lares recorded), visible while unlocked.
- More languages via contributed translations.
- Feedback from the closed test and the first users.

## 5. Version 1.2 – sync & Linux companion
Share rules and used time between several devices (e.g. phone and Linux laptops), without a
server of Lares' own: through an MQTT broker the user configures (own server or one they choose),
end-to-end encrypted.

**Design**
- **One counter per device, never a shared total:** each device publishes only its own used time
  per rule / group and usage period; every device adds up all counters and applies the limit to
  the sum (no conflicts – nobody overwrites another device's value). MQTT *retained* messages keep
  each device's latest value on the broker, so a device coming online has them right away.
- **Rules from the phone:** the phone stays the source of truth; it publishes the rule set
  (signed). Loosening still only happens on the phone, with the guardian code.
- **End-to-end encryption:** all devices share a key, handed over once by QR code when a device
  is paired. The broker only sees ciphertext, a random topic and timing – not what is blocked or
  how long anything was used.
- **No permanent connection on Android:** connect briefly about once a minute while the screen is
  on (publish own counter, read the others), plus an extra check when a limit is close.
- **Linux companion** (separate small project, e.g. Python or Rust + systemd): reads the rules,
  enforces them via browser policies (Firefox `WebsiteFilter`, Chromium `URLBlocklist` in `/etc/`,
  paths included) or `/etc/hosts` (domains only), and publishes the time the browser spends on
  matching sites. Time limits on Linux: start with "always blocked", add limits later.

**To decide**
- **Offline:** a device without connection only knows the others' last values, so a limit can be
  exceeded a little. Report a sync that fails for a long time (broker blocked, offline) to the
  guardian as a tamper event? Block completely without sync (stricter, annoying with bad
  reception)?
- **Library or own client:** Eclipse Paho / HiveMQ MQTT client (FOSS, more size) or a minimal own
  MQTT client over TLS (only connect, publish, subscribe, retain are needed).
- **ntfy as a second transport** (already supported for notifications, plain HTTPS: publish to a
  topic, read back with `since`; messages are cached for a limited time, enough if every device
  publishes regularly).
- **Known limit:** whoever controls a device with the key can in theory send lower values or
  block the broker; on an own Linux machine the user is root. Protection against impulses, not
  against determined bypassing – the guardian would have to hold the admin password for more.
