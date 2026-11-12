# Distribution

Target: **Google Play** and **F-Droid**. One codebase; product flavors (`play`, `fdroid`)
only if a store forces divergence – currently none is planned.

## Google Play
- **Accessibility Service**: not an accessibility tool → `android:isAccessibilityTool="false"`,
  Play Console Accessibility API declaration, a **prominent in-app disclosure** + consent
  screen before the user is sent to system settings, and a privacy policy explaining that
  window content/URLs are processed on-device only and never transmitted.
- **Device Admin**: allowed, but must be clearly disclosed (Android's activation screen shows
  Lares' explanation). Verify current Play policy on apps that make uninstalling harder before
  release (risk item).
- **Battery optimization**: Lares opens the system list instead of using
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (restricted on Play).
- **App list**: avoid `QUERY_ALL_PACKAGES` (restricted permission). Use `<queries>` with
  `MAIN/LAUNCHER` (listing launchable apps) and `VIEW https` (detecting browsers).
- `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, foreground service `specialUse`
  (Android 14+) need justification in the Play Console.
- Data safety form: no data collected by the developer; notifications go only to the endpoints the
  guardian configured.
- Accessibility services must not be enabled programmatically – user enables them.

## F-Droid
- App must be **FOSS** (OSI license) with all dependencies FOSS. Lares is licensed
  **GPL-3.0-or-later** (`/LICENSE`); dependencies must be GPL-3.0 compatible
  (Apache-2.0, MIT, BSD, LGPL, MPL-2.0 are fine).
- **No proprietary libraries**: no Google Play Services, Firebase, Crashlytics, Play
  In-App Review/Update, ML Kit, etc. Notification channels use `HttpsURLConnection` and an own
  SMTP client; QR codes use ZXing.
- No tracking/analytics. Builds from source by F-Droid; aim for reproducible builds
  (pinned dependency versions, no build-time timestamps).
- Likely anti-feature tag: `NonFreeNet` (optional Telegram channel; also possible for the
  other optional channels depending on the service). Acceptable.
- Metadata in `fastlane/metadata/android/<locale>/` (also usable for Play) – EN + DE.

## Build types
- `debug` – development (slow Compose, debuggable).
- `localRelease` – same as `release` (R8, not debuggable) but signed with the local debug key, to
  judge real performance on a device. Never published.
- `release` – unsigned here; signed by F-Droid / with the Play upload key.

## License notices in the app
- Apache-2.0 §4 requires shipping the license text (and NOTICE contents) with the APK – a link is
  not enough. The **AboutLibraries** Gradle plugin (offline mode, reproducible) generates the list
  of *all* libraries incl. transitive ones with full license texts at build time
  (`R.raw.aboutlibraries`, Settings → Info → Open-source libraries).
- Logo outlines come from the URW **P052** typeface (URW base 35 fonts, AGPL-3.0 with font
  exception), converted to paths – no font file is shipped; compatible with GPL-3.0.
- The GPL-3.0 text of Lares is generated from the repo's `LICENSE` into `R.raw.lares_license`
  (Gradle task `generateLicenseRes`) and shown offline.

## Consequences for development
- Dependency rule: every new dependency must be FOSS and Play-Services-free.
- Keep a privacy policy in the repo (`docs/privacy-policy.md`) and link it in both stores. It must
  describe only the current version and stay in sync with the in-app text (Settings → Info →
  Privacy policy, EN + DE); update both whenever a feature changes data handling.
