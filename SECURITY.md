# TheftGuard: security review

Reviewed 2026-11-05. Scope: every component, permission, stored value, log line and debug feature in the app.

## Threat model

| Attacker | What they have | What TheftGuard must hold against |
|---|---|---|
| **Thief, locked phone** (main case) | Physical phone, doesn't know the PINs | Can't power off via the menu, can't silence the alarm, alerts still go out, can't read stored data |
| **Thief, unlocked phone** (snatched in use) | Full UI access | Can't change PIN / contacts / gate / quiet mode without the TheftGuard PIN; switching protection off sends an alert |
| **Other apps on the phone** | Normal app permissions | Can't talk to our components, read our data, overlay or screenshot our screens, or fake system events |
| **Forensic / rooted** | Copy of app storage | PIN can't be brute-forced offline; contacts/IMEIs encrypted |

## Exposed surface (from the built APK's manifest)

| Component | Exported | Who can reach it | Verdict |
|---|---|---|---|
| `ui.MainActivity` | yes (launcher) | anyone can *open* it | ✅ takes no input from intents; every sensitive action needs the PIN |
| `gate.PowerGateService` | yes | **system only**: `BIND_ACCESSIBILITY_SERVICE` | ✅ |
| `admin.TheftGuardAdminReceiver` | yes | **system only**: `BIND_DEVICE_ADMIN` | ✅ |
| SMS "sent" receiver | registered at runtime | `RECEIVER_NOT_EXPORTED` + explicit-package, immutable PendingIntents | ✅ |
| Content providers / network | none | (no internet permission at all) | ✅ |

## Findings and fixes

| # | Finding | Risk | Fix |
|---|---|---|---|
| S1 | Debug build is `debuggable`: any computer with USB debugging can `run-as` and read app data | High for real use | **Release build** sets `isDebuggable = false`. Carry the release build, not the debug one (see *Release build*). |
| S2 | Quiet test mode (mutes the siren) and the window recorder existed in every build, switchable without the PIN | High: a thief with an unlocked phone could mute the alarm | Both compiled out of release (`BuildConfig.DEBUG`), their UI hidden; in debug builds switching either **on** needs the PIN |
| S3 | Recorder dumps on-screen system text (can include notification snippets) | Medium (privacy) | Debug-only (S2); texts cut to 40 chars; system windows only |
| S4 | Logcat output in all builds | Low (only readable over USB debugging) | Logcat only in debug builds |
| S5 | Full phone number + SMS text (location, IMEIs) in PendingIntent extras for the retry logic | Low-medium: PendingIntents appear in system dumps | Only a numeric ID travels in the intent; number/text stay in our process memory |
| S6 | PIN lockout used only wall-clock time: changing the clock on an unlocked phone could skip it | Medium | Second lockout clock on `elapsedRealtime` (unchangeable) tied to the boot counter, plus a clock-rollback guard |
| S7 | Wrong PINs typed in the app's own dialogs didn't count towards the alarm | Medium | ≥ 3 wrong → alarm, same as the overlay |
| S8 | Main screen (contacts, IMEIs, PIN dialogs) could be screenshotted, recorded, shown in Recents, or covered by another app's overlay (tapjacking) | Medium | `FLAG_SECURE` on the activity **and each dialog**; `setHideOverlayWindows(true)`; `filterTouchesWhenObscured` |
| S9 | Switching off the accessibility service or device admin was silent | Medium | Tamper alerts: SMS "protection switched off" / "alarm admin switched off"; warning text on Android's deactivate screen |

## Already in place (checked, no change needed)

- **PIN** never stored: `HMAC-SHA256(salt ‖ PIN)` with a non-exportable Keystore key (StrongBox when available). Constant-time compare. Escalating lockout (30 s → 1 h). Failures written with `commit()` before replying.
- **Contacts + IMEIs**: AES-256-GCM with a Keystore key, fresh IV per write, field name bound as AAD (no blob swapping), fails closed on tampering.
- **Backups off**: `allowBackup=false` + data-extraction rules exclude everything (cloud and device-to-device).
- **Direct Boot**: data in device-protected storage only holds the PIN *HMAC*, encrypted blobs and switches.
- **Overlay PIN pad**: `FLAG_SECURE`, never takes focus, can't be dismissed into a usable power menu; Keystore errors fail **closed**.
- **Accessibility**: honest `isAccessibilityTool=false`, reads system windows only, no key filtering (dropped when unused).
- **SMS** goes only to the numbers you saved; masked (`…1234`) in logs.
- **Minimal permissions**: no internet, no contacts/storage access, no camera/mic. The device admin asks only for watch-login + force-lock.

## Residual risks (can't be fixed by an app)

- **Hardware force restart** (Samsung ~7 s Side + Volume down, OnePlus ~10 s Power + Volume Up): can't be blocked; mitigated by the warning SMS, after-boot detection and alarm resume.
- **Unlocked phone in a thief's hands**: they can still uninstall or disable TheftGuard via Settings (after deactivating device admin). They'll trigger the tamper texts, but a determined thief can do it. Only device-owner mode could prevent it.
- **Advanced Protection mode (Android 17)** blocks the accessibility service.
- **SMS needs signal and an unlocked SIM.** A SIM PIN blocks alerts after a restart.
- **Debug builds stay debuggable** by design. Never carry one as your daily protection.

## Release build

```bash
# One-time: create YOUR signing key (keep it and its password private, never commit it)
"C:\Program Files\Android\Android Studio\jbr\bin\keytool" -genkeypair -v -keystore K:\Keys\theftguard-release.jks -alias theftguard -keyalg RSA -keysize 4096 -validity 10000
```

Then sign with it from Android Studio (Build → Generate Signed App Bundle / APK). `*.jks` and `*.keystore` are already in `.gitignore`.
