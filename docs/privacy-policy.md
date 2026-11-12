# Privacy Policy – Lares

_Last updated: 2026-11-12_

Lares works entirely on your phone.

## What Lares does not do
- No account, no analytics, no advertising, no tracking, no crash reporting services.
- No data is sold. Nothing is shared with third parties – except the notification services you
  set up yourself (see "Network").

## Data stored on the phone
- Your rules (blocked apps and websites, limits, groups), usage times, settings and a log of
  protection events (e.g. "clock changed", with time and details such as the time zones) are
  stored only on the phone, in the app's private storage.
- They are excluded from Android cloud backups and device-to-device transfers.
- The guardian code is stored only as a salted hash (password) or encrypted with an Android
  Keystore key that never leaves the phone (authenticator app). The recovery code is stored only
  as a salted hash.
- Lares disables Android autofill, so password managers never offer to save the guardian code.

## System permissions
- If the user turns on the Accessibility service or usage access, Lares uses them only to detect
  which app is in the foreground – and, while website blocking is on, the address shown in the
  address bar of supported browsers and, while "Guard system settings" is on, whether a system
  settings page or uninstall dialog is about Lares (no other screen content). This is processed on the phone in
  the moment and is neither stored nor transmitted.
- The optional device administrator role is used only to prevent uninstalling Lares; no other
  administrator rights are used.

## Network
- Lares connects to the internet only to send guardian notifications, and only to the services
  configured in the settings (Telegram, ntfy, email via the SMTP server you enter, Matrix or a
  webhook URL). Nothing is sent unless such a channel is set up.
- A notification contains only its text (e.g. a test message or "protection was disabled"), its
  time and the phone model. The data processing of the chosen service (e.g. Telegram) is subject to that
  service's own privacy policy.
- Some links open in your browser, which loads them – Lares itself sends nothing: Lares' neutral
  page shown when a website is blocked (`irgendwer008.github.io/Lares/blocked/`, a static page
  without scripts or external resources, hosted on GitHub Pages – GitHub's privacy policy
  applies), manufacturer tips (dontkillmyapp.com) and the pages of the open-source libraries.
- Channel tokens and email passwords are stored encrypted with an Android Keystore key on the phone.
- Exported rule files contain rules and groups only – no codes, tokens or usage data.
