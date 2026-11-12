# Roadmap

Pre-release milestones, in order. Done milestones are summarized in
[Architecture](architecture.md) ("Implemented").

## 1. Blocking
- **A – App blocking (Accessibility mode)** – done: foreground detection, block screen, daily
  limits incl. groups, countdown overlay, optional warnings, exempt apps.
- **B – Website blocking** – done (address-bar ids still to be verified on devices): URL-bar
  reading in supported browsers, blocking unsupported browsers.
- **C – Usage access mode** – foreground service polling `UsageStatsManager`, overlay block screen,
  notification for the service.
- **D – Protection & notifications** – restart after reboot / update, settings guard, tamper
  detection (service disabled, clock / time zone changed, gaps), uninstall-protection reporting,
  automatic sending to the guardian channels with retries.

## 2. Design tweaks
- UI polish across screens.
- App icon / logo.
- Countdown pill: small Lares logo icon next to the timer.
- Further icons and visual details as they come up.
