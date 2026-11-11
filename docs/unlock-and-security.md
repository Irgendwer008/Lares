# Unlock & Security

## Guardian code (chosen at setup)
### Static password
- The guardian types it on the device during setup (min. 4 characters). Stored as a salted
  PBKDF2-HMAC-SHA256 hash (310k iterations, ~0.3 s, computed on `Dispatchers.Default`). Never
  stored in plain text.

### TOTP
- The app generates a random 160-bit secret and shows an `otpauth://` QR code (and the key as
  text) for the guardian's authenticator app. It is stored only after the guardian entered a
  matching code, encrypted with an Android Keystore key.
- 30 s step, 6 digits, ±1 step for clock drift; codes at or before the last accepted one are
  rejected (no replay).

### Recovery code
- 20 random characters (Crockford Base32, 100 bits), shown once; can be copied or shared, never
  stored on the phone in plain text. Stored as a salted SHA-256 hash – with 100 random bits a slow
  KDF would add nothing but delay.
- Using it unlocks like the normal code, records a tamper event and forces setting up a new code
  (in unlocked mode; if that runs out first, the code has to be entered again).

### No password managers
- Android autofill is disabled for the whole app (activity window and every dialog window via
  `DisableAutofill()`), so password managers never offer to save or fill the guardian code.
  Secret fields use password input types (no keyboard learning).

### Wrong codes
- The 3rd wrong code in a row starts a 1 min waiting time; every further wrong code doubles it
  (2, 4, 8 … min, max. 24 h). A correct code resets the counter.
- A restart doesn't skip it: after a reboot the waiting time starts over in full.
- Tamper event "repeated wrong code" at the first waiting time and every 5th failure after
  (3, 8, 13 …).

## Unlocked mode and pause
- A correct code unlocks for **10 minutes**: every change is allowed. "Lock now" ends it early.
- A **pause** (only startable while unlocked; presets 5 min / 15 min / 1 h + custom, no maximum)
  suspends all blocking; it keeps running after locking and can be ended early without code.
- Both run on `elapsedRealtime`, so changing the clock or time zone doesn't affect them. There is
  no trustworthy clock across a reboot, so **a restart ends both** (ending them is tightening).
- Neither is reported to the guardian.

## Tighten vs. loosen policy
| Change | Needs code |
|---|---|
| Add rule / group / group member, lower a limit, switch a rule, group or protection on | No |
| Remove / disable a rule, raise a limit, take a member out of a group, delete a group | Yes |
| Choose the blocking mode for the first time / switch it later | No / Yes |
| Switch website blocking or a protection off | Yes |
| Change the daily reset time (either direction can grant a fresh quota) | Yes |
| Add a notification channel / edit or delete one | No / Yes |
| Change the guardian code | Yes |
| Export rules / import rules (import only adds) | No / No |

Taking a member out of a group is loosening unless its own limit is strictly lower (it no longer
shares the time). Moving a rule into a group that already contains the same target does nothing.

## Tamper protections (each toggleable; switching off = loosening)
1. **Prevent uninstalling** – device administrator (no admin policies used). While active,
   Android refuses to uninstall Lares; deactivation will be reported (`onDisabled`).
2. **Guard system settings** – needs the Accessibility service in every blocking mode. Blocks the
   App info page of Lares (force stop, uninstall, clear storage), its accessibility toggle, the
   device-admin page and the special-access pages for usage access / overlay. Detection by window
   package + content; OEM-specific, needs testing.
3. **Restart after reboot / update** – `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
   `MY_PACKAGE_REPLACED`; heartbeat timestamps reveal protection gaps.
4. **Notify guardian** – see [Notifications](notifications.md).

Items 1–4 are stored settings today; their services come with the blocking milestone (the
accessibility service and the device admin receiver are already declared).

## Tamper events
- Device admin disabled, accessibility service disabled, usage / overlay permission revoked,
  protection gap after reboot / force stop, system clock or time zone changed, repeated wrong
  code, recovery code used.

## Known limits (out of scope)
- Root, adb, safe mode (third-party apps don't run), factory reset. Safe mode and gaps are
  detected afterwards and reported, not prevented.
- After the recovery code was used, the setup screen stays until a new code is set; while locked,
  it can only be continued with the guardian code.
