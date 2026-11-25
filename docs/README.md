# Lares – Project Wiki

Lares is an Android app that helps users reduce social-media usage by blocking
user-configured apps and websites. Loosening restrictions requires a code that a
trusted person (friend, family member – the **guardian**) set up.

Disclaimer: This app would not have been possible without the use of AI. While
everything generated was checked over, there may still be bugs hidden in the code.
If you find any, feel free to open an issue [here](https://github.com/Irgendwer008/Lares/issues), if there isn't already one for your bug!

## Pages

| Page | Content |
|---|---|
| [Requirements](requirements.md) | Agreed product requirements (the "what") |
| [Roadmap](roadmap.md) | Versions: released, in progress, planned, ideas |
| [Architecture](architecture.md) | Stack, packages, data model, what is implemented (the "how") |
| [Blocking](blocking.md) | App blocking modes, website blocking, time limits |
| [Unlock & Security](unlock-and-security.md) | Password / TOTP, recovery, unlocked mode, tighten / loosen policy, tamper protection |
| [Notifications](notifications.md) | Guardian notification channels (Telegram, ntfy, email, webhook, Matrix) |
| [Localization](localization.md) | i18n rules (EN + DE, easy to extend) |
| [Distribution](distribution.md) | Google Play + F-Droid requirements, build types, versioning, signing, website |
| [Play Console](play-console.md) | Ready-to-paste answers for the Play Console forms |
| [Privacy Policy](privacy-policy.md) | Store privacy policy (in sync with the in-app text) |
| [Decisions](decisions.md) | Current design decisions and their reasons |
| [Open Questions](open-questions.md) | Things not decided yet |

Lares is **released** (1.0.0 on Google Play): docs describe the current state only, and stored data
must keep working across updates (see [Architecture](architecture.md), data model).

## Glossary

- **User** – the person whose usage is restricted (phone owner).
- **Guardian** – trusted person who holds the unlock code.
- **Rule** – a blocked app or website, always blocked, with a daily time limit or by a schedule.
- **Schedule** – named weekly time table with windows per weekday (full block / timer / free) and a
  mode outside the windows, picked by rules and groups; every change needs the code.
- **Window** – one time range of a schedule on a weekday (may cross midnight).
- **Group** – several rules sharing one limit and one on/off switch.
- **Template** – ready-made app + website combination (e.g. YouTube incl. alternative clients).
- **Loosening** – any change that reduces restrictions (needs the code).
- **Tightening** – any change that adds restrictions (for rules and groups it needs the code too, unless "Only loosening needs the code" is on).
- **Unlocked mode** – 10 minutes after a correct code in which every change is allowed.
- **Pause** – time-boxed suspension of all blocking, started while unlocked; ends automatically.
