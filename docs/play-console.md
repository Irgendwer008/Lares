# Google Play Console – answers

Ready-to-paste answers for the Play Console forms. Store texts (title, descriptions, changelog)
are in `fastlane/metadata/android/<locale>/`. Play policies change – check each form's current
wording before submitting.

## Store listing
- **App name / short / full description:** `fastlane/metadata/android/en-US/` and `de-DE/`.
- **Category:** Productivity. **Tags:** digital wellbeing, focus.
- **Graphics:** app icon (512 × 512) and feature graphic (1024 × 500) in
  `fastlane/metadata/android/en-US/images/`, rendered from the logo SVGs with
  `java -Djava.awt.headless=true tools/StoreGraphics.java` (run again after logo changes).
  Still needed: 2–8 phone screenshots per language (main screen with groups and usage bars, block
  screen, add screen with templates, settings, setup with the color choice) in
  `fastlane/metadata/android/<locale>/images/phoneScreenshots/`.
- **Privacy policy:** `https://irgendwer008.github.io/Lares/privacy-policy.html`
- **Contact email** (shown publicly): entered directly in the Play Console – deliberately not kept
  in the repository.

## App content
- **App access:** "All functionality is available without special access." Note for reviewers:
  *No account or login. On first start the app asks to set a guardian code – any password with at
  least 4 characters works. After setup, the app is unlocked for 10 minutes; later, enter the same
  password to unlock.*
- **Ads:** No.
- **Content rating:** utility / productivity app; no violence, sexual content, gambling, user
  generated content or user-to-user communication. Shares no location. Allows no purchases.
- **Target audience:** 13 and older (13–15, 16–17, 18+). Not under 13, so the Families policy
  (stricter rules, e.g. for the Accessibility API and device admin) doesn't apply.
- **News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.

## Data safety
- **Does your app collect or share any of the required user data types?** No.
  - Lares stores everything only on the device. The only data leaving the device are guardian
    notifications (event text, its time, phone model), sent directly from the device to a service
    the user configures and chooses (Telegram bot, ntfy server, own SMTP server, Matrix homeserver,
    webhook URL). The developer receives nothing and runs no server. Play's definition of
    "collection" covers data transmitted off the device *to the developer or third parties*; if the
    form's current help text counts user-configured destinations as sharing, declare **"App
    activity → Other actions"** as *shared*, *optional*, purpose *App functionality*.
- **Is all user data encrypted in transit?** Yes (HTTPS / SMTP over TLS only).
- **Can users request that data is deleted?** Not applicable (no data collected); all data is
  removed by clearing the app's data or uninstalling.

## Permissions and APIs
### Accessibility API (`isAccessibilityTool = false`)
- **Core functionality:** "Lares is a digital-wellbeing app that blocks apps and websites the user
  chose. The AccessibilityService detects which app is in the foreground to show a block screen
  over blocked apps and to count daily usage time; while the user's 'website blocking' setting is
  on, it reads only the address bar of supported browsers to block chosen websites; while 'guard
  system settings' is on, it recognizes Lares' own system-settings pages (e.g. its App info page)
  to block tampering. No other screen content is read. Nothing is stored or transmitted."
- **Prominent disclosure:** shown in the app before the system accessibility settings open
  (dialog "Accessibility service" with an explanation and "Agree and continue").
- **Video** (YouTube, unlisted), show in order:
  1. Main screen → setup issue "Accessibility service is off" → tap → the disclosure dialog → agree.
  2. System accessibility settings → turn on Lares.
  3. Add a blocked app (e.g. a template) → open that app → the block screen appears.
  4. Turn on website blocking → open a blocked site in Chrome → the block screen appears.

### Foreground service type `specialUse`
- **Used by:** `UsageMonitorService`, only in the alternative "Usage access + overlay" blocking
  mode chosen by the user.
- **Justification:** "App blocker chosen by the user: while the user's blocking mode is 'Usage
  access', the service polls UsageStatsManager once per second (screen on only) to detect the app in
  the foreground and show a block screen over apps the user chose to block. It must run
  continuously; no other foreground service type fits this use. A persistent notification shows
  while it runs." (The same text is declared in the manifest property
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.)
- **Video:** choose the "Usage access + overlay" mode in the settings, grant usage access and
  overlay, show the notification "Lares is blocking", open a blocked app → block screen.

### Device administrator
- Used only for "Prevent uninstalling": no admin policies are requested (`device_admin.xml` is
  empty), activation is the system screen with Lares' explanation, and deactivating it is always
  possible (the user is told that the guardian will be notified).
- **Check before submitting** whether the current Play policy allows a device-admin uninstall
  protection for this kind of app (digital wellbeing / self-control); if not, the protection has to
  be removed from the Play build (risk noted in [Distribution](distribution.md)).

### Other permissions (no declaration form)
- `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW` – user-granted special access for the alternative
  blocking mode. `POST_NOTIFICATIONS` – requested only when limit warnings are switched on.
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `INTERNET` – install-time.
- No `QUERY_ALL_PACKAGES` (the app list uses `<queries>` intents instead), no
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the battery list opens instead).
