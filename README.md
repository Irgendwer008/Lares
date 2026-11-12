<p align="center">
  <img src="docs/Logo_long.svg" alt="Lares" width="280">
</p>

<p align="center"><b>Blocks distracting apps and websites – loosening needs a guardian code.</b></p>

Lares is a free and open-source Android app that helps you use social media less. You choose the
apps and websites to block, always or after a daily time limit. Adding restrictions is always
possible; removing or loosening them needs a code that only a trusted person – your **guardian**,
e.g. a friend or family member – knows.

> **Status:** version 1.0.0, the first release – coming to Google Play and F-Droid.

## Features
- **Privacy first** – Lares only asks for the permissions your current settings need. It doesn't
  connect to the internet at all, except to send guardian notifications if you set them up.
- **App and website blocking** – always blocked or with a daily time limit, with a full block
  screen. Website blocking reads the address bar of supported browsers (Chrome, Firefox, Brave,
  Edge, Samsung Internet, DuckDuckGo, …); other browsers are blocked while it's on. No VPN, so your
  own VPN keeps working.
- **Groups** – several apps and websites can share one limit (e.g. YouTube app + youtube.com +
  alternative clients). **Templates** for popular social apps, including alternative clients.
- **Guardian code** – a password or an authenticator app (TOTP) set up by the guardian, plus a
  one-time recovery code. A correct code unlocks for 10 minutes; pauses can be started from there.
- **Tamper protection** – prevent uninstalling (device administrator), guard Lares' own system
  settings pages, and notify the guardian about tampering (Telegram, ntfy, email, Matrix or a
  webhook): permissions turned off, protection gaps, clock changes, repeated wrong codes.
- **Countdown and warnings** before a limit runs out; usage bars on the main screen.
- **Two blocking modes** – Accessibility service (recommended) or usage access + overlay.
- English and German; Lares colors or your wallpaper colors (Material You).

## Privacy
Lares works entirely on your phone: no account, no analytics, no tracking, no ads. It only asks for
the permissions your current settings need, and it connects to the internet only to send guardian
notifications – **if** you set them up in the settings. For details, see the
[privacy policy](https://irgendwer008.github.io/Lares/privacy-policy.html).

## Permissions
| Permission | Why |
|---|---|
| Accessibility service (one of the two modes) | Detect the app in the foreground, read the address bar of supported browsers (website blocking), recognize Lares' own settings pages (settings guard). Nothing else of the screen is read; nothing is stored or sent. |
| Usage access + display over other apps (the other mode) | Alternative blocking mode without the Accessibility service. |
| Device administrator (optional) | Only to prevent uninstalling Lares; no other admin rights are used. |
| Notifications (optional) | Only if limit warnings are switched on. |
| Internet | Only used for guardian notifications, if you set up a channel. |
| Run at startup | Restart protection after a reboot or update. |

## Building
Requirements: JDK 17 or newer and the Android SDK (compile SDK 37).

```sh
./gradlew assembleDebug           # debug build
./gradlew testDebugUnitTest       # unit tests
./gradlew lintDebug               # lint (missing translations are errors)
./gradlew assembleLocalRelease    # optimized, debug-signed – for testing on a device
./gradlew assembleRelease         # release (unsigned unless keystore.properties exists)
```

Release signing and versioning are described in [Distribution](docs/distribution.md).

## Documentation
The project wiki lives in [`docs/`](docs/README.md): requirements, architecture, blocking,
unlock & security, notifications, localization, distribution and design decisions.

## Contributing
Contributions are welcome – see [CONTRIBUTING.md](CONTRIBUTING.md).

## License
Lares is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).
Third-party libraries and their licenses are listed in the app (Settings → Open-source libraries).
