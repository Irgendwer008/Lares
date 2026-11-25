# Roadmap

Where Lares stands and what comes next. Finished work is described in more detail in
[Architecture](architecture.md) ("Implemented").

| Version | Theme | Status |
|---|---|---|
| [1.0.0](#version-100--first-release-released) | First release: app and website blocking, guardian code, protection | Released on Google Play (2026-11-15) |
| [1.1](#version-11--blocking-gaps-sharing-french-released) | Blocking gaps, multiple countdowns, sharing, French | Released on Google Play |
| [1.2](#version-12--plans--pause-code-in-progress) | Weekly / daily plans, pause code | In progress |
| [1.3](#version-13--sync--linux-companion-planned) | Sync between devices, Linux companion | Planned |
| [Later](#later--ideas) | Ideas | To be decided |

## Version 1.0.0 – first release (released)
- **Blocking:** app blocking in Accessibility mode (foreground detection, block screen, daily
  limits incl. groups, countdown overlay, optional warnings, exempt apps); website blocking
  (address bar of supported browsers, unsupported browsers blocked); Usage access mode
  (foreground service polling `UsageStatsManager`, block screen and countdown via the overlay
  permission).
- **Protection & notifications:** restart after reboot / update, settings guard, tamper detection
  (permissions lost, clock / time zone changed, gaps, boots without Lares), uninstall-protection
  reporting, automatic sending to the guardian channels with retries.
- **Design:** adaptive and themed icon, wordmark, Lares theme or wallpaper colors (own setup
  screen with previews), usage bars, empty state, icons for all settings entries.
- **Release:** full review pass, versioning and release signing ([Distribution](distribution.md)),
  GitHub Pages (privacy policy, page for blocked websites), store listing EN / DE and Play Console
  declarations ([Play Console](play-console.md)), `README.md` and `CONTRIBUTING.md`, device test
  (Motorola), tag `v1.0.0`.
- **F-Droid:** rejected for now (projects need more than three months of development history;
  reviewers saw no new features over similar apps). Recipe kept in `docs/fdroid/de.lares_app.yml`.

## Version 1.1 – blocking gaps, sharing, French (released)
**Blocking**
- Picture-in-picture and split screen: the other visible windows are checked too (Accessibility
  mode), incl. the address of a browser in the other half.
- Block screen: "Available again at midnight" when the limits restart at 00:00.
- Website rule paths: repeated slashes and percent-encoding are normalized before matching.

**Countdown & warnings**
- Several countdown pills at once (one per limit, stacked, with the app icon).
- One warning notification per limit that follows the remaining time (also when it grows again).

**Setup & main screen**
- Setup: blocking mode on its own page, with website blocking and the protections switchable
  there; "Back" on every page.
- Main screen: info box while website blocking is off ("Turn on" / "Change app blocking mode",
  "Dismiss"; shown again after website blocking was on in between).
- Info boxes as tiles on wide screens (2 columns from 600 dp, 3 from 1200 dp).

**Rules export & import**
- Export: save as a file or share (share sheet); greyed out without rules.
- Authenticator (TOTP) code can move to a new phone with the export, encrypted with the recovery
  code ("Include authenticator code"; "Restore from export" in the first setup). On import into a
  set-up phone the guardian chooses: keep the current code or use the file's.

**Policy**
- Broadening a website rule (`youtube.com/shorts` → `youtube.com`) and cancelling a pending
  reset-time change no longer need the code. Switching the blocking mode keeps needing it.

**Languages & cleanups**
- French translation (app and store texts).
- No blocking main-thread reads of the settings (small settings mirror), duplicate strings merged,
  proper plurals in the import result.

### Decided against (for now)
- Detecting a supported browser whose address bar can't be read (view id changed by an update) –
  known limit in [Blocking](blocking.md).
- Block screen over only one half of a split screen – not possible reliably.

## Version 1.2 – plans & pause code (in progress)
**Done**
- Daily reset always at the set time: the clock-forward check uses a clock anchor per boot (before,
  the reset could come hours late if a period was first noticed late, e.g. phone unused around
  midnight).

**Carried over from 1.1**
- **Device tests** on other manufacturers (Samsung, Pixel, Xiaomi): add their settings packages
  (e.g. Samsung's `com.samsung.accessibility`, Huawei / Honor's system manager), verify the
  address-bar ids and the settings-page detection, test Usage access mode with aggressive
  background limits.
- **Android 13+ "active apps" panel** (quick settings) can stop Usage access mode: detect it
  faster than the gap check, and point users to Accessibility mode.
- **Device-admin policy** check (Google Play).

**Weekly and daily plans** (more custom rules)
- Per rule / group: different limits per weekday (e.g. 30 min on school days, 90 min at the
  weekend) and/or time windows (blocked at certain times of day, e.g. 21:00–07:00 or during school
  hours), alone or combined with a daily limit.
- Policy: like limits today – tightening (shorter limit, more blocked time) applies at once,
  loosening needs the code or waits for the reset.
- To decide: windows across midnight, how the main screen shows the plan ("blocked until 07:00",
  today's limit), whether plans can be shared between rules (templates like "school days").

**Pause code** (second TOTP)
- An extra authenticator code, set up by the guardian while unlocked (second entry in the
  authenticator app, own secret). Given to the user, it allows a short pause (e.g. 5–15 min,
  length set by the guardian) of the blocking – no rule or settings changes.
- Each code works once (TOTP counter as today); the pause ends on its own, and the guardian can
  be notified when it's used (guardian channels).
- To decide: pause everything or only the app / website that is blocked right now; a maximum per
  day; does pause time count toward the limits; the export (move it like the guardian TOTP?).

## Version 1.3 – sync & Linux companion (planned)
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

## Later – ideas
- Open-count limits.
- A protection log in the app (the tamper events Lares recorded), visible while unlocked.
- More languages via contributed translations.
- Feedback from the first users.
- F-Droid: apply again once the project has a longer history.
