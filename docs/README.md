# Lares – Project Wiki

Lares is an Android app that helps users reduce social-media usage by blocking
user-configured apps and websites. Loosening restrictions requires a code that a
trusted person (friend, family member – the **guardian**) set up.

## Pages

| Page | Content |
|---|---|
| [Requirements](requirements.md) | Agreed product requirements (the "what") |
| [Roadmap](roadmap.md) | Milestones until the first release |
| [Architecture](architecture.md) | Stack, packages, data model, what is implemented (the "how") |
| [Blocking](blocking.md) | App blocking modes, website blocking, time limits |
| [Unlock & Security](unlock-and-security.md) | Password / TOTP, recovery, unlocked mode, tighten / loosen policy, tamper protection |
| [Notifications](notifications.md) | Guardian notification channels (Telegram, ntfy, email, webhook, Matrix) |
| [Localization](localization.md) | i18n rules (EN + DE, easy to extend) |
| [Distribution](distribution.md) | Google Play + F-Droid requirements, build types, license notices |
| [Privacy Policy](privacy-policy.md) | Store privacy policy (in sync with the in-app text) |
| [Decisions](decisions.md) | Current design decisions and their reasons |
| [Open Questions](open-questions.md) | Things not decided yet |

The project is **pre-release**: docs describe the current state only, and data formats / the
database are changed in place (no migrations, no compatibility code) until the first release.

## Glossary

- **User** – the person whose usage is restricted (phone owner).
- **Guardian** – trusted person who holds the unlock code.
- **Rule** – a blocked app or website, always blocked or with a daily time limit.
- **Group** – several rules sharing one limit and one on/off switch.
- **Template** – ready-made app + website combination (e.g. YouTube incl. alternative clients).
- **Loosening** – any change that reduces restrictions (needs the code).
- **Tightening** – any change that adds restrictions (no code needed).
- **Unlocked mode** – 10 minutes after a correct code in which every change is allowed.
- **Pause** – time-boxed suspension of all blocking, started while unlocked; ends automatically.
