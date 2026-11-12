# Contributing to Lares

Thanks for helping! Bug reports, translations and code are all welcome.

## Reporting bugs and ideas
Open an issue on GitHub. For bugs, please include the phone model, Android version, blocking mode
(Accessibility service or usage access) and, if possible, steps to reproduce. For website blocking
issues, name the browser and its version.

**Security issues** (ways to get around a restriction without the guardian code) are welcome as
issues too – Lares has no server and no user data to protect, so there is nothing to keep secret.

## Code
- **Project layout:** pure-Kotlin logic in `core/` (unit-tested, no Android imports), Android
  implementations in `platform/` and `blocking/`, UI in `ui/`, wiring in `di/`. See
  [Architecture](docs/architecture.md).
- **Tighten vs. loosen:** every change that reduces restrictions must go through the guardian-code
  check (`UnlockGate` / `ChangePolicy`). See [Unlock & Security](docs/unlock-and-security.md).
- **Tests:** add unit tests for logic in `core/`, especially for anything security-relevant.
- **Before a pull request:** `./gradlew testDebugUnitTest lintDebug` must pass.
- **Dependencies:** only free / open-source libraries without Google Play Services or tracking
  (F-Droid requirement), compatible with GPL-3.0.
- **No tracking, analytics or network use** beyond the guardian notifications.
- Every source file starts with the license header (`SPDX-License-Identifier: GPL-3.0-or-later`).
- Keep the docs in `docs/` in sync with the code; they describe the current state only.

## Translations
UI texts live in `app/src/main/res/values*/strings.xml` (English default, German in
`values-de`). A missing translation is a lint error, so new texts need every language.
To add a language: create `values-xx/strings.xml` and add an entry to `AppLanguages`
(`ui/AppLanguages.kt`). Rules: [Localization](docs/localization.md).

## License
By contributing, you agree that your contributions are licensed under the
[GNU General Public License v3.0 or later](LICENSE).
