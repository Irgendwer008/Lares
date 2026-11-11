# Blocking

## App blocking modes

### Accessibility Service mode
- Listens to `TYPE_WINDOW_STATE_CHANGED`; `event.packageName` = foreground app.
- On block: `performGlobalAction(GLOBAL_ACTION_HOME)` and launch the block screen.
- Pros: instant, battery friendly. Cons: user must enable it manually in system settings;
  Google Play requires an Accessibility API declaration and prominent disclosure.
- The system restarts an enabled accessibility service automatically after reboot.

### Usage Stats + Overlay mode
- Needs `PACKAGE_USAGE_STATS` (special app access) and `SYSTEM_ALERT_WINDOW`.
- Foreground service polls `UsageStatsManager.queryEvents()` (~every 1 s) for
  `ACTIVITY_RESUMED` / `MOVE_TO_FOREGROUND` events.
- Shows an overlay / block activity. Slight delay and battery cost.
- Android 14+: foreground service needs a type (`specialUse`) declared in the manifest.
- Some OEMs (Xiaomi, Huawei, Samsung…) kill background services aggressively →
  onboarding must guide the user to battery-optimization exemptions.

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
- Reset at the configured local time (default midnight, see requirements §2). Clock / time-zone changes are recorded as tamper events
  (see [Unlock & Security](unlock-and-security.md)).
- Warn shortly before the limit is reached (e.g. 1 min left).

## Block screen
- Shows what is blocked and why (always-on / limit reached), remaining time until reset,
  and an "Unlock" entry that leads to code entry.
