# Unlock & Security

## Unlock methods (chosen at setup)
### Static password
- Guardian types it on the device during setup; stored as salted hash
  (PBKDF2-HMAC-SHA256 with high iteration count, or Argon2id via library). Never plaintext.
### TOTP
- App generates a random secret, shows an `otpauth://` QR code **once** for the guardian
  to scan in an authenticator app. Secret stored encrypted with an Android Keystore key.
- 30 s step, 6 digits, accept ±1 step for clock drift, reject reuse of the last accepted code.

### Recovery code
- Long random code generated at setup, shown once, stored only as hash.
- Using it unlocks like a normal code and forces re-setup of the unlock method.

### Brute-force protection
- Exponential back-off after failed attempts (e.g. 5 failures → 1 min, doubling).
- Too many failures → tamper event "repeated wrong code".

## After a successful unlock
- **Pause**: presets (5 min, 15 min, 1 h) + custom, no maximum; end stored as both wall clock and
  `elapsedRealtime` so changing the clock doesn't extend it.
- **Edit session**: loosening allowed for a chosen time (presets 5 / 10 / 30 min + custom,
  no maximum) or until closed early.
- Neither is reported to the guardian.

## Tighten vs. loosen policy
| Change | Needs code |
|---|---|
| Add rule, lower limit, enable protection, add alert channel | No |
| Remove/disable rule, raise limit, disable protection | Yes |
| Switch blocking mode, disable website blocking | Yes |
| Change unlock method, remove/edit alert channel | Yes |
| Change daily reset time (either direction) | Yes |
| Export rules | No |
| Import rules that only tighten / import that loosens anything | No / Yes |

## Tamper protections (each toggleable; disabling = loosening)
1. **Uninstall protection** – Device Admin. While active, Android refuses uninstall.
   `onDisableRequested` shows a warning; `onDisabled` fires a tamper alert.
2. **Settings-page guard** – requires the Accessibility Service in every blocking mode
   (enabling it in Usage Stats mode prompts the user to enable Accessibility too). Via Accessibility: block App info of Lares (Force stop,
   Uninstall, Clear storage), the Accessibility toggle page for Lares, Device-admin page,
   and special-access pages for Usage Stats / overlay. Detection by window package
   (`com.android.settings`) + content (app label). OEM-specific, needs testing.
3. **Restart after reboot / update** – `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
   `MY_PACKAGE_REPLACED`. Periodic heartbeat timestamps allow detecting protection gaps.
4. **Guardian alerts** – see [Notifications](notifications.md).

## Tamper events
- Device admin disabled, accessibility service disabled, usage/overlay permission revoked,
  protection gap after reboot/force stop, system clock or time zone changed,
  repeated wrong code, recovery code used.

## Known limits (out of scope)
- Root, adb, safe mode (third-party apps don't run), factory reset.
- Safe mode / gaps are detected afterwards and reported, not prevented.
