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
- The **first setup** ends in unlocked mode too (10 minutes from confirming the recovery code),
  so the first rules can be added and corrected right away. Recovery and code changes keep the
  unlocked mode that is already running.
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
   Android refuses to uninstall Lares. Deactivating it shows "Your guardian will be notified" in
   the system dialog (if notifications are on) and is reported from `onDisabled` – sent right away
   (uninstalling may follow), queued as a fallback.
2. **Guard system settings** – needs the Accessibility service in every blocking mode. While
   locked, Lares' own pages in the system settings show the block screen ("This settings page is
   protected"): App info (force stop, uninstall, clear storage), its accessibility page, the
   device-admin page and the special-access pages (usage access, overlay, battery), and the
   package installer's uninstall dialog for Lares (updates stay possible). Recognized without view
   ids (Settings is partly Compose since Android 14): page title "Lares", or "Lares" as a
   non-clickable text (app header; found by walking the window, max. 600 nodes – the system
   text search doesn't see Compose) – lists show it in a clickable row and stay usable
   (`SettingsGuard`; OEM settings differ, verify on devices). Debug builds log every settings
   window with the guard status and the check result (`LaresBlocking`). When
   a setup issue is opened from the main screen, the guard lets the settings through only while
   that issue is still unfixed, until Lares is opened again, and at most 2 min (otherwise the
   permission it protects couldn't be granted). Once fixed, the user is still on that page: it
   stays open until they leave the settings, at most 30 s. Inactive in unlocked mode.
3. **Notify guardian** – see [Notifications](notifications.md).

**Restart after reboot / update** is always on (no switch – the system restarts the
Accessibility service anyway): `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` start the process,
which restarts Usage access mode and runs the boot checks.

## Tamper detection
Recorded in the tamper log only while Lares is set up and **locked** (changes in unlocked mode
are the guardian's; a pause doesn't count as unlocked). `ProtectionMonitor`, pure rules in
`core/protection`:
- **Permission lost** – Accessibility service, usage access, overlay, device administrator: only
  if the current settings need it and it was working before (never granted = setup issue, not
  tampering). Checked every 30 s while blocking runs, 2 s after the Accessibility service is
  turned off, every 15 min by WorkManager (also when nothing else of Lares runs) and when Lares
  is opened.
- **Protection gap** – the blocking of the chosen mode starts again more than 10 min after its
  last heartbeat (every minute with the screen on, and at screen on / off) in the same boot, and
  that heartbeat came with the screen on (stopped while in use, e.g. force stop). After the
  screen went off, a later start says nothing about when the phone was used again → not reported.
- **Boots without Lares** – the boot counter grew by more than one since Lares last ran (e.g.
  safe mode, where third-party apps don't run).
- **Clock changed** – `TIME_SET` and the offset wall clock − elapsed realtime moved by more than
  2 min (network time corrections are smaller). Limits are unaffected anyway (`ResetSchedule`).
- **Time zone changed** – `TIMEZONE_CHANGED` (or a different zone at process start): from → to.
- **Repeated wrong code**, **recovery code used** – see above.
- Bookkeeping (heartbeat, boot counter, clock offset, zone, working permissions) lives in its own
  `SharedPreferences` (`ProtectionStore`), not in the app state.

## Known limits (out of scope)
- Root, adb, safe mode (third-party apps don't run), factory reset. Safe mode and gaps are
  detected afterwards and reported, not prevented.
- After the recovery code was used, the setup screen stays until a new code is set; while locked,
  it can only be continued with the guardian code.
