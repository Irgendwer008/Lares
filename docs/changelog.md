# Changelog

All notable changes to Lares, newest first. The store "What's new" texts
(`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, max. 500 characters) list
only the most important changes and link here
(<https://irgendwer008.github.io/Lares/changelog.html>); this file is the complete version. Keep it up to date with every
user-visible change, and paste a version's section into its GitHub release when tagging.

## 1.2.0 – in progress

### Schedules (new)
- Rules and groups now have three modes: **Always blocked**, **Daily limit** or **Schedule**. A
  rule inside a group follows the group's mode.
- A schedule is a named weekly time table, shared by any number of rules and groups. On each
  weekday it has **windows** – *full block*, *timer* (minutes within the window) or *free* – and
  a mode for the time **outside the windows** (free, full block, or a timer for the rest of the
  day).
- Windows may cross midnight (they belong to the day they start on). Timer windows of the same day
  can **share one budget**. A window keeps its budget even when it runs across the daily reset.
- **Week editor** like a calendar: all seven days side by side, a "now" line, opens at the current
  time.
  - Tap an empty spot to add a window, or long-press and drag to draw one.
  - Tap a window to edit it: days (several at once), times, mode, minutes, shared timer.
  - Long-press to move a window (also to another day), long-press its edge to resize it. Both snap
    to 15 minutes and day columns while dragging.
  - "Cancel" discards all changes after a confirmation.
- The main screen shows each schedule's current state ("Blocked until 17:00", a timer bar,
  "Free until 21:00"); the block screen says when it's available again (with the weekday if not
  today). The countdown also runs before a schedule switches to a full block.
- Schedules are in **Settings → Schedules**. Every change to a schedule needs the guardian code;
  only unused schedules can be deleted.
- **Export / import** includes schedules. Older Lares versions can still read the file (rules
  with a schedule become "always blocked" there). Files move between the app and the browser
  extension in both directions.

### Guardian code
- **Every change to rules and groups now needs the guardian code by default** – adding, editing,
  removing, switching on / off. The new setting **"Only loosening needs the code"** (Settings →
  Guardian; changing it needs the code) restores the previous behavior, where adding and
  tightening were free.
- Adding rules or importing a file while locked offers to enter the code right away.

### First setup
- New **welcome screen** with the color choice.
- A short, skippable **tour**: what Lares blocks, the three modes, schedules (with an example week),
  the guardian code.
- **Progress circles** at the bottom show where you are; the tour splits into numbered circles.
- New last page **"All set"**, with a pointer to the browser extension.

### Browser extension
- Link to **[Lares Browser Edition](https://github.com/Irgendwer008/Lares-BrowserEdition)**, the
  browser extension for computers: on the last setup page and in Settings → Info.

### Tablets and landscape
- Main screen: the info boxes stack in a left column, the rules fill the rest; group cards in two
  columns on very wide screens.
- Schedule editor: name and "outside the windows" next to the full-height calendar.
- Templates and apps in the add screen as a grid.
- Settings, lists, info screens, setup and the block screen keep a readable width.

### Fixes
- The **daily reset** now always happens at the set time. Before, it could come hours late on days
  after the phone wasn't used around the reset time.
- Texts that said "adding restrictions is always possible" were updated for the new default.

### Under the hood
- Database version 2 (automatic migration from 1.0 / 1.1; schema history in `app/schemas/`).

## 1.1.0 – 2026-11-18

### Blocking
- **Picture-in-picture and split screen** (Accessibility mode): blocked apps are blocked there too,
  and the time of visible apps counts – also the address of a browser in the other half.
- Block screen: "Available again at midnight" when the limits restart at 00:00.
- Website rules also match addresses with repeated slashes (`//`) and percent-encoding.

### Countdown and warnings
- **Several countdowns at once** (one per limit, stacked, each with the app's icon).
- One warning notification per limit that follows the remaining time – also when it grows again
  (e.g. after the limit was raised).

### Setup and main screen
- Setup: the blocking mode on its own page, with website blocking and the protections switchable
  right there; "Back" on every page.
- Main screen: info box while website blocking is off ("Turn on" / "Change app blocking mode",
  "Dismiss").
- Info boxes side by side on wide screens.

### Rules export and import
- Export: save as a file or **share** it; greyed out without rules.
- The **authenticator code can move to a new phone** with the export, encrypted with the recovery
  code ("Include authenticator code"; "Restore from export" in the first setup). Passwords aren't
  moved (just type them again).
- Importing a file with an authenticator code into a set-up phone: the guardian chooses between
  keeping the current code and using the file's.

### Guardian code
- **Fewer changes need the code:** broadening a website rule (e.g. `youtube.com/shorts` →
  `youtube.com`) and cancelling a pending reset-time change. Switching the blocking mode still
  needs it.

### Languages
- **French** translation (app and store texts).

## 1.0.0 – 2026-11-15

First release on Google Play.

- **App and website blocking** – always or after a daily time limit, with a full block screen.
  Website blocking reads the address bar of supported browsers; other browsers are blocked while
  it's on.
- **Groups** sharing one limit, **templates** for popular apps incl. their websites and
  alternative clients.
- **Guardian code** – a password or an authenticator app (TOTP), plus a one-time recovery code.
  Loosening needs the code; a correct code unlocks for 10 minutes, and pauses can be started.
- **Two blocking modes** – Accessibility service (recommended) or usage access + overlay.
- **Tamper protection** – prevent uninstalling, guard Lares' own system settings pages, detect
  tampering (permissions turned off, protection gaps, clock changes, repeated wrong codes) and
  notify the guardian via Telegram, ntfy, email, Matrix or a webhook.
- **Countdown and warnings** before a limit runs out; usage bars on the main screen.
- English and German; Lares colors or wallpaper colors (Material You).
