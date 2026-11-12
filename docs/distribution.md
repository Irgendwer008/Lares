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
- `release` – unsigned unless `keystore.properties` exists (then signed with the Play upload key).

## Versioning
- `versionName` is semantic `major.minor.patch`, set once in `app/build.gradle.kts`
  (`appVersion`). The `versionCode` is derived: major × 10 000 + minor × 100 + patch (0.1.0 → 100,
  1.2.3 → 10203), so it always grows; minor and patch stay below 100.
- Each release gets a git tag `v<versionName>` (F-Droid's update check can follow tags) and a
  changelog file `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.

## Signing
- **F-Droid** builds from source and signs with its own key, or – with reproducible builds –
  publishes the developer-signed APK if F-Droid's build matches it byte for byte.
- **Google Play:** Play App Signing; the upload key stays with the developer. Release signing reads
  `keystore.properties` in the project root (never committed – `.gitignore` covers it and `*.jks`):
  ```properties
  storeFile=keys/lares-upload.jks
  storePassword=…
  keyAlias=lares-upload
  keyPassword=…
  ```
  Create the key once with `keytool -genkeypair -v -keystore keys/lares-upload.jks -alias
  lares-upload -keyalg RSA -keysize 4096 -validity 10000`. `keys/` is ignored by git; keep a
  backup of the key file and both passwords outside the repository (e.g. a password manager).
- Play App Signing: Google signs the APKs delivered by Play with its own app signing key; F-Droid
  signs with its own key. So switching between the Play and the F-Droid version needs a reinstall
  (Android doesn't update an app signed with another key).
- The app id `de.lares_app` is final.

## Website (GitHub Pages)
- Served from the repository's `docs/` folder (Settings → Pages → Deploy from a branch, branch
  `master`, folder `/docs`): `https://irgendwer008.github.io/Lares/`.
- **Privacy policy URL** for both stores: `https://irgendwer008.github.io/Lares/privacy-policy.html`.
- **Neutral page** opened instead of a blocked website (`SAFE_PAGE_URL`):
  `https://irgendwer008.github.io/Lares/blocked/` – `docs/blocked/index.html`, self-contained (no
  scripts, no external resources, light / dark).

## License notices in the app
- Apache-2.0 §4 requires shipping the license text (and NOTICE contents) with the APK – a link is
  not enough. The **AboutLibraries** Gradle plugin (offline mode, reproducible) generates the list
  of *all* libraries incl. transitive ones at build time (`R.raw.aboutlibraries`, Settings → Info
  → Open-source libraries, grouped by author, with license text and source link per library).
- Offline mode stores license **names only, no texts**. The texts are bundled by SPDX id
  (`res/raw/apache_2_0.txt`, the original Apache-2.0 file; currently every dependency is
  Apache-2.0). When a dependency with another license is added, add its text there and to the
  map in `LibrariesScreen` – otherwise only the license name is shown.
- Logo outlines come from the URW **P052** typeface (URW base 35 fonts, AGPL-3.0 with font
  exception), converted to paths – no font file is shipped; compatible with GPL-3.0.
- The GPL-3.0 text of Lares is generated from the repo's `LICENSE` into `R.raw.lares_license`
  (Gradle task `generateLicenseRes`) and shown offline.

## Consequences for development
- Dependency rule: every new dependency must be FOSS and Play-Services-free.
- Keep a privacy policy in the repo (`docs/privacy-policy.md`, published via GitHub Pages) and link
  it in both stores. It must
  describe only the current version and stay in sync with the in-app text (Settings → Info →
  Privacy policy, EN + DE); update both whenever a feature changes data handling.
