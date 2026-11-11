# Lares – Project Wiki

Lares is an Android app that helps users reduce social-media usage by blocking
user-configured apps and websites. Loosening restrictions requires a code that a
trusted person (friend, family member – the **guardian**) set up.

## Pages

| Page | Content |
|---|---|
| [Requirements](requirements.md) | Agreed product decisions (the "what") |
| [Architecture](architecture.md) | Stack, modules, data model (the "how") |
| [Blocking](blocking.md) | App blocking modes, website blocking, time limits |
| [Unlock & Security](unlock-and-security.md) | Password / TOTP, recovery, strictness rules, tamper protection |
| [Notifications](notifications.md) | Guardian alert channels (Telegram, ntfy, webhook, Matrix) |
| [Localization](localization.md) | i18n rules (EN + DE, easy to extend) |
| [Distribution](distribution.md) | Google Play + F-Droid requirements and constraints |
| [Decision Log](decisions.md) | Chronological record of decisions and their reasons |
| [Open Questions](open-questions.md) | Things not decided yet |

## Glossary

- **User** – the person whose usage is restricted (phone owner).
- **Guardian** – trusted person who holds the unlock code.
- **Rule** – a blocked app or website, optionally with a daily time limit.
- **Loosening** – any change that reduces restrictions (requires code).
- **Tightening** – any change that adds restrictions (no code needed).
- **Pause** – time-boxed suspension of all blocking, ends automatically.
- **Edit session** – time-boxed unlocked state in which rules may be loosened.
