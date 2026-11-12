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

## 2. Design tweaks
- UI polish across screens.
- App icon / logo.
- Countdown pill: small Lares logo icon next to the timer.
- Further icons and visual details as they come up.

## 3. Publishing
- prepare everything needed for publishing on Google Play Store *and* f-droid.
- Make github repo more user and contributor friendly.
- Publish app on Google Play Store.
- Publish app in F-Droid