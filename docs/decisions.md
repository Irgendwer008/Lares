# Decisions

Current design decisions and their reasons, by topic. When a decision changes, its entry is
updated in place.

## Product
| Decision | Reason |
|---|---|
| Tightening is free, loosening needs the guardian code | Encourages stricter rules; the guardian only needs to be involved for loosening |
| Rule types in v1: always blocked or daily time limit | Schedules and open-count limits deferred |
| Daily reset time configurable (default 00:00); any change needs the code and applies after the next reset | Night owls; moving the reset in either direction can grant a fresh quota |
| Groups with a shared daily limit and a common switch; members use the group's limit | Sum up time across app + website + alternative clients; switch related rules at once |
| A target can be a single rule and a group member at the same time; both limits apply | Adding a group never changes existing rules; the stricter limit always wins |
| Taking a member out of a group is loosening (unless its own limit is stricter); deleting a group deletes its members | Leaving ends the time sharing; keeps the model simple |
| Templates for popular social apps incl. alternative clients (NewPipe, ReVanced, LibreTube …), optionally as a group | One-tap setup; alternative clients would otherwise be an easy bypass |
| Backup: export rules and groups (no secrets, no usage data); import only adds | Device change without re-entering everything; add-only import never loosens, so it needs no code |

## Guardian code & unlocking
| Decision | Reason |
|---|---|
| Guardian code: static password or TOTP, chosen at setup | Password is simple; TOTP allows remote, non-reusable codes |
| Recovery code at setup, hashed with salted SHA-256 | Avoids permanent lock-out; 100 random bits can't be brute-forced, so a slow KDF would only add delay |
| Recovery code is copied or shared, never saved on the phone | Sharing sends it straight to the guardian; a stored file would stay on the owner's phone |
| Correct code → unlocked mode (10 min, "Lock now"); a pause is started from unlocked mode | The guardian unlocks once and can change anything |
| Pause: presets + custom, no maximum | The guardian decides the length |
| No guardian notification for unlocking or pauses (only tamper events) | The guardian entered the code, so they already know |
| 3rd wrong code → 1 min waiting time, doubling up to 24 h | Brute-force protection without locking out after a single typo |
| Timers run on `elapsedRealtime`; a reboot ends unlocked mode / pause and restarts a waiting time | No trustworthy clock across reboots – a reboot must never extend a loosening state or skip a waiting time |
| Autofill disabled app-wide | The guardian code must never be saved on the owner's phone |

## Blocking
| Decision | Reason |
|---|---|
| App blocking mode selectable: Accessibility service (recommended) or Usage access + overlay | Accessibility is instant, battery friendly and needed for website blocking anyway; Usage access is the alternative for users who don't want to grant it. Device Owner rejected (needs reset / adb) |
| No VPN / DNS blocking | The user's own VPN must always be usable; Private DNS gets overridden by VPNs anyway |
| Website blocking = URL-bar reading + blocking unsupported browsers | Only option on stock devices without VPN / root / Device Owner |
| One `BlockingController` for both modes; the foreground app only from the chosen mode's service | Same rules, counting and block screen in both modes; no double counting when both services run (e.g. settings guard in Usage access mode) |
| Website blocking only in Accessibility mode | It needs the Accessibility service anyway – then its mode is faster and more battery friendly than Usage access |
| Usage access mode shows the normal block screen (activity), not a drawn overlay | Allowed from the background with the overlay permission; one block screen to maintain |
| Address bar read only while website blocking is on; only the address-bar node, never other content | Least access to window content |
| Blocked website: both block screen buttons ("Stay in browser", "Close browser") open a neutral page in the browser as a normal link | Otherwise the blocked page stays the open tab and blocks the browser again on every start; back presses depend on the history (several blocked pages, closing the browser) |
| The settings guard always needs the Accessibility service (also in Usage access mode) | Settings pages can only be read via Accessibility |
| Tamper protections (prevent uninstall, settings guard, restart after reboot, notify guardian) – each toggleable | User choice |
| Main screen lists all missing setup at once (mode, permissions, device admin – assuming the recommended Accessibility mode until one is chosen); accessibility service and device admin are declared ahead of their logic | The user sees everything blocking still needs in one go; components must exist to be enabled and checked |
| Prominent disclosure before opening the accessibility settings | Google Play policy |
| Full block screen (not just a jump to the home screen) | Clear reason and next steps; "Go to Lares" opens the app (to unlock or pause – the code may already be entered, since unlocked mode keeps blocking) |
| Blocking stays active in unlocked mode | Unlocked mode is for changing rules; using blocked apps needs an explicit pause |
| Limit countdown overlay in the last 5 minutes (switchable); warning notifications opt-in | Unobtrusive heads-up without needing the notification permission by default |
| Notification permission requested only when a warning is switched on, returned (`revokeSelfPermissionOnKill`, Android 13+) when all are off | Least privilege |
| Essential apps (Lares, launcher, phone / emergency, keyboard, system UI, Settings as a whole) can't be blocked | Prevents locking oneself out of calls, input or the system |
| Battery-optimization exemption recommended via the system list | OEMs kill background work; the one-tap dialog's permission is restricted on Play |

## Notifications
| Decision | Reason |
|---|---|
| Channels: Telegram, ntfy, email (SMTP), webhook, Matrix | SMS rejected (Play restrictions); Signal and WhatsApp have no API usable for private guardians |
| No extra network libraries: `HttpsURLConnection` + own minimal SMTP client; HTTPS / TLS only, no redirects | Small, FOSS-clean, credentials never go to another host |
| `INTERNET` permission only for guardian notifications | Lares itself works offline |
| Settings UI for blocking mode and protections available before their services exist | Complete settings UI up front |

## Icons
| Decision | Reason |
|---|---|
| No brand logos and no icon fetching: installed app icon, otherwise a letter icon (template brand color, dark letter on light colors, outline on black) | Logos are trademarked / copyrighted (GPL, F-Droid NonFreeAssets, Play IP risk); fetching icons would tell each website about the user |
| Display names for template apps that aren't installed | Readable labels instead of package names |

## Platform & distribution
| Decision | Reason |
|---|---|
| Kotlin + Compose, minSdk 26 | ~95 % device coverage, modern stack |
| UI EN + DE, docs EN, strict i18n rules | Easy extension to more languages |
| Distribute via Google Play and F-Droid | Reach + FOSS audience; implies FOSS-only deps, no Play Services, no `QUERY_ALL_PACKAGES` |
| License GPL-3.0-or-later | Keeps forks open source (no closed / tracking re-releases); compatible with the Apache-2.0 dependencies |
| License notices via AboutLibraries (offline); GPL text bundled from `LICENSE` at build time | Apache-2.0 / GPL require shipping the license texts; covers all transitive libraries |
| Android backup and device-to-device transfer disabled | Keystore-bound secrets can't be restored elsewhere; rules move via the explicit export |
| App bundle not split by language | In-app language switch and guardian messages in another language need all languages; Play Core isn't FOSS |
| Whole-app R8 optimization in release builds | Partial optimization scopes broke Kotlin's package-private classes |
| Pre-release: no DB migrations or compatibility code; schema changed in place | No released version, no user data to keep; migrations start with the first release |
