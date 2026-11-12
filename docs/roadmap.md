# Roadmap

Pre-release milestones, in order. Done milestones are summarized in
[Architecture](architecture.md) ("Implemented").

## 1. Blocking
- **A – App blocking (Accessibility mode)** – done: foreground detection, block screen, daily
  limits incl. groups, countdown overlay, optional warnings, exempt apps.
- **B – Website blocking** – done (address-bar ids still to be verified on devices): URL-bar
  reading in supported browsers, blocking unsupported browsers.
- **C – Usage access mode** – done: foreground service polling `UsageStatsManager`, block screen
  and countdown via the overlay permission, service notification.
- **D – Protection & notifications** – done (settings-page detection still to be verified on
  devices): restart after reboot / update, settings guard, tamper detection (permissions lost,
  clock / time zone changed, gaps, boots without Lares), uninstall-protection reporting,
  automatic sending to the guardian channels with retries.

## 2. Design tweaks – done
- App icon: adaptive icon (parchment background, clay-orange "L" from `docs/Logo.svg`, inside the
  66 dp safe circle; also the themed monochrome icon). Wordmark (`docs/Logo_long.svg`) in the main
  screen title, on the setup intro and in the About dialog.
- Countdown pill with a small Lares "L" next to the timer.
- Lares theme (clay orange with olive and teal accents on parchment, logo brown) or wallpaper
  colors (Material You, Android 12+) – chosen on their own first setup screen with two previews,
  changeable in the settings.
- Main screen: usage bars ("12 / 30 min"), empty state with the logo, clearly visible buttons in
  the cards. Icons for all settings entries; templates with installed apps first.
- Lares "L" on the block screen and as notification icon; the lock stays where it means "needs
  the code".

## 3. Release preparation & publishing
- Full review pass (bugs, security, leftovers, docs in sync with the code) – done.
- Versioning and release signing setup – done (see [Distribution](distribution.md)).
- GitHub Pages: privacy policy and the neutral page for blocked websites – done (Pages still to be
  switched on in the repository settings).
- Prepare everything needed for publishing on Google Play *and* F-Droid:
  - Google Play: store listing (EN / DE texts, screenshots, feature graphic from the wordmark),
    Accessibility API declaration (incl. video), special-use foreground service declaration,
    data safety form, privacy policy URL, check of the current device-admin policy.
  - F-Droid: `fastlane/metadata` (descriptions, screenshots), reproducible-build check.
  - Done: store texts EN / DE in `fastlane/metadata/android/`, Play Console answers in
    [Play Console](play-console.md). Open: screenshots, device-admin policy check.
- Make the GitHub repository more user and contributor friendly – `README.md` and
  `CONTRIBUTING.md` done; screenshots follow with the store listing.
- On-device tests: settings guard on other manufacturers (Samsung, Pixel), a real guardian alert
  end to end, Usage access mode on a manufacturer with aggressive background limits.
- Publish the app on Google Play.
- Publish the app in F-Droid.
