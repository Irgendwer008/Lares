# Blocking

Design of the blocking services (blocking milestone). Already in place: the policy logic
(`PolicyEngine`, `UrlMatcher`, `ResetSchedule`), the declared accessibility service / device admin
receiver, and the setup checks on the main screen.

## App blocking modes

### Accessibility Service mode
- Listens to `TYPE_WINDOW_STATE_CHANGED`; `event.packageName` = foreground app.
- On block: `performGlobalAction(GLOBAL_ACTION_HOME)` and launch the block screen.
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
- Read the address bar node of **supported browsers** by view ID, e.g.
  - Chrome: `com.android.chrome:id/url_bar`
  - Firefox: `org.mozilla.firefox:id/mozac_browser_toolbar_url_view`
  - Samsung Internet: `com.sec.android.app.sbrowser:id/location_bar_edit_text`
  - Brave: `com.brave.browser:id/url_bar`, Edge: `com.microsoft.emmx:id/url_bar`
  (IDs must be verified per browser version; keep them in a data table, not in code.)
- Match normalized host (strip `www.`, scheme) against domain rules incl. subdomains;
  path rules (e.g. `youtube.com/shorts`) match by prefix.
- **Unsupported browsers:** list all apps handling `ACTION_VIEW https://…` and block every
  one without an adapter while website blocking is on.
- Custom Tabs are rendered by the host browser → covered. In-app WebViews are only
  partially detectable → documented limitation.
- Ignore the URL bar while it is being edited (only evaluate committed URLs) to avoid
  false blocks while typing.

## Daily time limits
- `UsageTracker` accumulates foreground time per rule; for websites the time a matching
  URL is in the foreground.
- Reset at the configured local time (default midnight, see [Requirements](requirements.md) §3).
  Clock / time-zone changes never grant a new quota (`ResetSchedule.advance()`) and are recorded
  as tamper events (see [Unlock & Security](unlock-and-security.md)).
- Group members share one counter: the group's foreground time is the sum of all members, counted
  once even if several members match at the same time (app + website).
- Warn shortly before the limit is reached (e.g. 1 min left).

## Block screen
- Shows what is blocked and why (always-on / limit reached), remaining time until reset,
  and an "Unlock" entry that leads to code entry.
