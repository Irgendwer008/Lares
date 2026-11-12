# Blocking

Design of the blocking services. **Implemented:** app blocking in both modes (Accessibility,
Usage access), website blocking, block screen, time limits (incl. groups), countdown overlay and
optional warnings, settings guard and tamper detection (see [Unlock & Security](unlock-and-security.md)).

One `BlockingController` decides for both modes. Each mode's service attaches itself as host; the
foreground app is taken only from the host of the chosen mode, which also shows the block screen
and countdown.

## App blocking modes

### Accessibility Service mode
- Listens to `TYPE_WINDOW_STATE_CHANGED`; only events whose class is an **activity** of the
  package change the foreground app – keyboards, the notification shade, popups and dialogs of
  other apps are ignored (otherwise e.g. an opening keyboard would stop time counting).
  Screen on / off via broadcasts.
- On block: the block screen is started directly over the blocked app (own task, so the app is
  paused underneath); "Close" / back go to the home screen. Debounced against relaunching.
- Debuggable builds log foreground changes and decisions (Logcat tag `LaresBlocking`).
- Pros: instant, battery friendly. Cons: user must enable it manually in system settings;
  Google Play requires an Accessibility API declaration and prominent disclosure (shown before
  the main screen opens the accessibility settings).
- Website blocking reads the address bar, the settings guard the page title and texts equal to
  "Lares" on settings / uninstall windows (`canRetrieveWindowContent`, `flagReportViewIds`).
- The system restarts an enabled accessibility service automatically after reboot.

### Usage Stats + Overlay mode
- Needs `PACKAGE_USAGE_STATS` (special app access) and `SYSTEM_ALERT_WINDOW`.
- `UsageMonitorService` (foreground service, type `specialUse`, minimal-priority notification
  "Lares is blocking") polls `UsageStatsManager.queryEvents()` every second while the screen is
  on: the app of the latest `ACTIVITY_RESUMED` event is in the foreground (`ForegroundTracker`:
  only events since the last poll plus 2 s overlap; clock set back → starts over).
- Block screen: the same `BlockActivity` – starting it from the background is allowed because
  Lares may draw over other apps. Countdown pill as `TYPE_APPLICATION_OVERLAY`. Up to ~1 s delay.
- `UsageMonitorStarter` runs the service exactly while this mode is chosen and setup is complete
  (follows the settings; `START_STICKY`, started again after boot / update). Android 12+ may
  refuse a start from the background (no exemption) – then it starts the next time Lares is opened.
- Some OEMs (Xiaomi, Huawei, Samsung…) kill background services aggressively →
  the main screen recommends the battery-optimization exemption (both modes; required in practice
  for Usage Stats mode on these devices), opens the system list
  (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` – the one-tap dialog needs a Play-restricted
  permission) and links manufacturer tips (dontkillmyapp.com, opened in the browser: the
  manufacturer's page if the site has one – fixed list in `DontKillMyApp` – otherwise the start page).

## Website blocking (Accessibility mode only)
- Not available in Usage access mode: the switch is disabled there (the setting is kept and
  applies again after switching back), the Add screen explains it with a "Change app blocking
  mode" button. Whoever needs the Accessibility service anyway is better off with its mode.
- While website blocking is off or unavailable, the main screen marks website rules and groups
  containing websites with a red "Not blocked – …" warning.
- Reads only the address bar of **supported browsers** by view id (`SupportedBrowsers` table:
  Chromium family `url_bar` – Chrome, Brave, Edge, Vivaldi, Kiwi, Cromite / Bromite; Samsung
  Internet `location_bar_edit_text`; Firefox family `mozac_browser_toolbar_url_view` – Firefox,
  Beta, Nightly, Fennec F-Droid, Mull; DuckDuckGo `omnibarTextInput`; Opera `url_field`).
  Ids can change with browser updates → verify on devices and adjust the table.
- Read on window-content changes of a supported browser, bundled to one read per 300 ms; only
  while website blocking is on. While the bar is focused (user typing), nothing is evaluated.
- Matching via `UrlMatcher`: host incl. subdomains, `www.` / scheme ignored, path rules by segment.
  Browsers that show only the domain (e.g. Firefox) can't distinguish path rules like
  `youtube.com/shorts` – domain rules work everywhere.
- On a blocked website: block screen over the browser ("reddit.com is blocked") with "Stay in
  browser" (opens a neutral page – `SAFE_PAGE_URL`, Lares' own static page on GitHub Pages, `docs/blocked/index.html` – in the
  same browser like a normal link; Chromium browsers reuse one tab for it), "Close browser" (same,
  then the home screen) and "Go to Lares". So the blocked page is no longer the open tab when the
  browser is opened again; for 5 s the old URL is ignored while the neutral page loads. Time limits count while a
  matching site is shown.
- **Unsupported browsers:** apps that open a neutral `https://example.com/` link (generic web
  filter, so not apps that only handle their own domain) and have no adapter are blocked
  completely while website blocking is on.
- Custom Tabs are rendered by the host browser → covered. In-app WebViews are not detectable →
  limitation.

## Daily time limits
- `UsageCounter` (in memory, `UsageRepository` saves it) accumulates foreground time per rule and
  group; for websites the time a matching URL is in the foreground.
- Reset at the configured local time (default midnight, see [Requirements](requirements.md) §3).
  Clock / time-zone changes never grant a new quota (`ResetSchedule.advance()`): a period only
  ends at its stored end, not before most of its length really passed (monotonic time, so setting
  the clock forward doesn't help), and a period after a zone change lasts at least 23 h. Both are
  also recorded as tamper events (see [Unlock & Security](unlock-and-security.md)).
- Moving a rule into / out of a group carries its used time along (the destination counter gets
  at least the source's time), so moving never starts a fresh quota.
- Group members share one counter: the group's foreground time is the sum of all members, counted
  once even if several members match at the same time (app + website).
- Time counts once per second while the app is in the foreground and the screen is on (steps are
  capped at 2 s, so a stalled timer never counts minutes at once); not during a pause.
- Translucent countdown pill, centered at the top, during the last 5 minutes (switchable); optional
  notifications 5 / 1 minute before the end.

## Block screen
- Shows what is blocked and why (always blocked / limit reached, group if any), when the limits
  restart, "Close" (home screen) and "Go to Lares" (opens the app).

## Known limits
- Picture-in-picture and split screen: only the app of the last opened screen counts, so a
  blocked video can keep playing in a PiP window or the other half of the screen.
- A supported browser whose address bar can't be read (e.g. its view id changed in an update)
  is only subject to app rules until the `SupportedBrowsers` table is updated.
- Settings apps of other manufacturers (e.g. Samsung's separate accessibility app) may need
  their packages added to the settings guard; the Android 13+ "active apps" panel in the quick
  settings can stop Usage access mode (reported as a gap once Lares runs again).

## Exempt apps
- Never blocked: Lares, the home screen, phone / emergency, keyboards, system UI, the Settings app
  as a whole, permission and install dialogs (`ExemptApps`, computed from what is installed).
  Home screens, dialers and settings apps count only if they are system apps or the chosen
  default, keyboards only if enabled – any app could declare such a role to escape blocking.
