# Blocking

Design of the blocking services. **Implemented:** app and website blocking in Accessibility mode
with block screen, time limits (incl. groups), countdown overlay and optional warnings.
**Next:** Usage access mode, settings guard / tamper detection.

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
- Website blocking / settings guard additionally need `canRetrieveWindowContent` in
  `accessibility_service.xml` (currently off, since no window content is read yet).
- The system restarts an enabled accessibility service automatically after reboot.

### Usage Stats + Overlay mode
- Needs `PACKAGE_USAGE_STATS` (special app access) and `SYSTEM_ALERT_WINDOW`.
- Foreground service polls `UsageStatsManager.queryEvents()` (~every 1 s) for
  `ACTIVITY_RESUMED` / `MOVE_TO_FOREGROUND` events.
- Shows an overlay / block activity. Slight delay and battery cost.
- Android 14+: foreground service needs a type (`specialUse`) declared in the manifest.
- Some OEMs (Xiaomi, Huawei, Samsung…) kill background services aggressively →
  the main screen recommends the battery-optimization exemption (both modes; required in practice
  for Usage Stats mode on these devices), opens the system list
  (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` – the one-tap dialog needs a Play-restricted
  permission) and links manufacturer tips (dontkillmyapp.com, opened in the browser).

## Website blocking (requires Accessibility Service)
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
  browser" (opens a neutral page – `SAFE_PAGE_URL`, google.com until Lares has its own page – in the
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
- `UsageTracker` accumulates foreground time per rule; for websites the time a matching
  URL is in the foreground.
- Reset at the configured local time (default midnight, see [Requirements](requirements.md) §3).
  Clock / time-zone changes never grant a new quota (`ResetSchedule.advance()`) and are recorded
  as tamper events (see [Unlock & Security](unlock-and-security.md)).
- Group members share one counter: the group's foreground time is the sum of all members, counted
  once even if several members match at the same time (app + website).
- Time counts once per second while the app is in the foreground and the screen is on (steps are
  capped at 2 s, so a stalled timer never counts minutes at once); not during a pause.
- Translucent countdown pill, centered at the top, during the last 5 minutes (switchable); optional
  notifications 5 / 1 minute before the end.

## Block screen
- Shows what is blocked and why (always blocked / limit reached, group if any), when the limits
  restart, "Close" (home screen) and "Go to Lares" (opens the app).

## Exempt apps
- Never blocked: Lares, launchers, dialers / emergency, keyboards, system UI, the Settings app as a
  whole, permission and install dialogs (`ExemptApps`, computed from what is installed).
