# Distribution

Target: **Google Play** and **F-Droid**. One codebase; product flavors (`play`, `fdroid`)
only if a store forces divergence – currently none is planned.

## Google Play
- **Accessibility Service**: not an accessibility tool → `android:isAccessibilityTool="false"`,
  Play Console Accessibility API declaration, a **prominent in-app disclosure** + consent
  screen before the user is sent to system settings, and a privacy policy explaining that
  window content/URLs are processed on-device only and never transmitted.
- **Device Admin**: allowed, but must be clearly disclosed. Verify current Play policy on
  apps that make uninstalling harder before release (risk item).
- **App list**: avoid `QUERY_ALL_PACKAGES` (restricted permission). Use `<queries>` with
  `MAIN/LAUNCHER` (listing launchable apps) and `VIEW https` (detecting browsers).
- `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, foreground service `specialUse`
  (Android 14+) need justification in the Play Console.
- Data safety form: no data collected; alerts go only to guardian-configured endpoints.
- Accessibility services must not be enabled programmatically – user enables them.

## F-Droid
- App must be **FOSS** (OSI license) with all dependencies FOSS. Lares is licensed
  **GPL-3.0-or-later** (`/LICENSE`); dependencies must be GPL-3.0 compatible
  (Apache-2.0, MIT, BSD, LGPL, MPL-2.0 are fine).
- **No proprietary libraries**: no Google Play Services, Firebase, Crashlytics, Play
  In-App Review/Update, ML Kit, etc. Use plain HTTP (OkHttp/Ktor) for all channels,
  ZXing (or similar FOSS) for QR codes.
- No tracking/analytics. Builds from source by F-Droid; aim for reproducible builds
  (pinned dependency versions, no build-time timestamps).
- Likely anti-feature tag: `NonFreeNet` (optional Telegram channel). Acceptable.
- Metadata in `fastlane/metadata/android/<locale>/` (also usable for Play) – EN + DE.

## Consequences for development
- Dependency rule: every new dependency must be FOSS and Play-Services-free.
- Keep a privacy policy in the repo (`docs/privacy-policy.md`) and link it in both stores.
