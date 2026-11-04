# TheftGuard: implementation plan

Status (2026-11-04): plan approved, D1–D3 decided.
- **Phase 0 (recorder):** done on the S23 FE.
- **Phase 1 (shutdown gate):** done and tested on the S23 FE. Covers locked and unlocked, side key and quick panel. Fast enough that the menu can't be tapped. Back re-locks. It works before the first unlock after a restart.
- **2026-11-05: one universal power-menu detector for all Android 16+ phones** (class name + translated Power off/Restart labels), replacing the per-OEM detectors.
- **Phase 2 (alarm) and Phase 3a (SMS, live location, power-off-attempt warning):** working on the S23 FE and 12R. Open issues are in FAILURES.md.
- **Next:** 3b (airplane mode / Location toggle), Fold 7, Phase 5 onboarding.

---

## 1. What the three phones run (checked 2026-11-04)

| Phone | Android now | Next update | API level now |
|---|---|---|---|
| Galaxy Z Fold 7 (UK) | Android 17 / One UI 9 (stable) | — | 37 |
| Galaxy S23 FE (India) | Android 16 / One UI 8 | One UI 9 (Android 17): "later this year"; Samsung Japan lists Jan 2027. This is its last big update. | 36 |
| OnePlus 12R (India) | Android 16 / OxygenOS 16 | OxygenOS 17 (Android 17): not out yet. This is its last big update. | 36 |

**Please confirm on each phone:** Settings → About phone → Software information. If any phone is older than Android 16, tell me.

**SDK choice:**
- `minSdk = 36`: the lowest Android version the app will install on. All three phones are on 16+.
- `targetSdk = compileSdk = 37`: we build against Android 17, so the app gets 17's rules on the Fold 7 and the S23 FE and 12R keep working after they update.

## 2. What changed in my research (please read)

### 2.1 Built-in power-off lock: Samsungs have it when LOCKED only
**Corrected 2026-11-04 after testing:** the S23 FE (One UI 8.5) *does* ask for the lock-screen PIN/password before Power off or Restart **when the phone is locked**. It does **not** ask when the phone is unlocked and in use. Krish's earlier "no built-in lock" was based on trying it unlocked.

What that means for TheftGuard's gate on Samsung:
- **Unlocked (phone snatched while in use):** Samsung does nothing. TheftGuard's gate is the **only** protection. This is the common snatch-theft case.
- **Locked:** Samsung already asks for the lock-screen PIN. TheftGuard adds its separate PIN on top. That only matters if the thief knows your lock-screen PIN (for example, by watching you unlock).
- **Fold 7:** probably the same (One UI 9); to check.
- **Decided 2026-11-04:** keep both. The TheftGuard gate runs whether locked or unlocked, so powering off a locked phone needs both PINs.
- **OnePlus 12R:** **Krish confirmed on 2026-11-04 that it has "Require password to power off" built in.** Use it. The built-in check runs as part of the system, so it's stronger than our accessibility gate and isn't affected by OxygenOS killing background apps.
  - Krish still to verify: it also covers **Restart**, and it works when the phone is **locked**.
  - Our gate on the 12R becomes an optional backup, built last. The Samsung detectors come first.
  - Hardware force-restart still can't be blocked, even by the built-in lock.

### 2.2 Android 17 Advanced Protection blocks our accessibility service
If **Advanced Protection** is turned on (Settings → Security → Advanced Protection), Android 17 only allows accessibility services from real accessibility tools such as screen readers. It also revokes the permission from other apps when the mode is switched on. TheftGuard is not an accessibility tool, and claiming to be one would be lying to the OS, so we won't do that.

**What this means:** you can use Advanced Protection or TheftGuard's power gate on a phone, not both. The app will check for it and warn you. This doesn't affect the device-admin alarm or the SMS alert.

### 2.3 EncryptedSharedPreferences is deprecated
The spec says EncryptedSharedPreferences. AndroidX deprecated it in `security-crypto` 1.1.0, and the replacement it points to is the Android Keystore directly.

**Proposed instead (no extra dependency):**
- **PIN:** don't store or encrypt it. Store a salted **PBKDF2-HMAC-SHA256 hash** of it. To check a PIN, hash the attempt and compare. Even with the storage file, nobody can recover the PIN without brute force, and they get a limited number of tries.
- **Emergency contacts and other secrets:** encrypt with **AES-256-GCM**, using a key kept inside the **Android Keystore**. The key is hardware-backed (TEE/StrongBox) on all three phones and never leaves it. Store the encrypted result in normal SharedPreferences.

This is a change to the decided architecture, so it needs your OK (decision D2).

### 2.4 Confirmed constraints (from the spec, still true)
- **IMEI:** since Android 10, `TelephonyManager.getImei()` needs `READ_PRIVILEGED_PHONE_STATE`. Only system apps, carrier apps, and **device owner** apps can hold it. A normal app can't read the IMEI. → Decision D3.
- **Wi-Fi / mobile data:** `WifiManager.setWifiEnabled()` does nothing for normal apps targeting API 29+. There is no public API to turn mobile data on. A device owner app can *stop the user* from changing Wi-Fi, mobile network or airplane-mode settings, but still can't force mobile data on. → Feature 4.
- **SMS needs no data.** It goes over the cellular voice/signalling network. As long as there is a SIM and signal, the alert goes out even with Wi-Fi and data off. Airplane mode still kills it, which is why the Feature 4 options matter.
- **Hardware force-restart** (holding power + volume down) is handled below Android, by the firmware/PMIC. No app can block it, and the app's UI will say so.

## 3. Proposed project structure

Package: `dev.krishgoel.theftguard` (matches krishgoel.dev). Single module.

```
K:\Projects\theftguard\
├── PLAN.md                   this file
├── settings.gradle.kts
├── build.gradle.kts          root build: plugin versions only
├── gradle/libs.versions.toml version catalog: every dependency version in one place
├── gradlew, gradlew.bat, gradle/wrapper/   pinned Gradle version
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/xml/
        │   ├── device_admin.xml                  policies we ask for (watch-login, force-lock)
        │   └── power_gate_service.xml            accessibility config: which events we listen to
        └── java/dev/krishgoel/theftguard/
            ├── TheftGuardApp.kt                  Application class
            ├── device/OemInfo.kt                 Samsung / OnePlus / Fold check, built-in gate detection
            ├── security/
            │   ├── PinHasher.kt                  Keystore HMAC of PIN + constant-time compare
            │   └── SecureStore.kt                Keystore AES-GCM wrapper
            ├── gate/                             FEATURE 1
            │   ├── PowerGateService.kt           AccessibilityService
            │   ├── PowerMenuDetector.kt          interface: "is this event the power menu?"
            │   ├── SamsungPowerMenuDetector.kt
            │   ├── OnePlusPowerMenuDetector.kt
            │   ├── GateState.kt                  short "you're allowed" window after a correct PIN
            │   └── PinGateOverlay.kt             PIN pad drawn as an accessibility overlay
            ├── admin/                            FEATURE 2
            │   └── TheftGuardAdminReceiver.kt    onPasswordFailed / onPasswordSucceeded
            ├── alarm/
            │   └── AlarmService.kt               foreground service, USAGE_ALARM, re-forces volume
            ├── alert/                            FEATURE 3
            │   ├── LocationFetcher.kt            platform LocationManager
            │   └── SmsAlertSender.kt             multipart SMS to each contact
            └── ui/
                ├── MainActivity.kt               status dashboard: what's on, what's missing
                ├── OnboardingActivity.kt         step-by-step permissions, per OEM
                └── SettingsActivity.kt           PIN, contacts, IMEI
```

### Dependencies: as few as possible
- **UI: plain Android Views + the platform `Theme.DeviceDefault`** (recommended). No Compose and no AppCompat, so the only dependency is the Kotlin stdlib. You'll see how Android works underneath instead of through a framework. Downside: more XML layout files. → Decision D1.
- **Location: platform `LocationManager.getCurrentLocation()`** instead of Google's fused location. Fused location would add a Google Play Services dependency. The platform API is less battery-smart, but we only call it when an alert fires, so that doesn't matter here.
- **Testing:** JUnit only, for the pure-logic parts (PIN hashing, detectors fed recorded events).

### Why the overlay instead of an Activity for the PIN pad
An accessibility service can draw a `TYPE_ACCESSIBILITY_OVERLAY` window **without** the "Display over other apps" permission. It also shows above the lock screen and the SystemUI power menu. A normal Activity launched from the background is blocked by Android 10+ background-activity-start rules and may appear *behind* the keyguard. The overlay avoids both problems.

## 4. Build order

Each phase ends with: real-device test steps, plus which of the three phones it's been tested on.

### Phase 0: Toolchain and the "spy" build (before any protection)
1. Install Android Studio. This machine has no JDK, Android SDK or adb yet. Android Studio bundles its own JDK.
2. Create the Gradle skeleton and a blank MainActivity, install it over USB, and confirm `adb devices` sees all three phones.
3. **Power-menu recorder:** a debug-only mode of the accessibility service that just *logs* every window event (package, class name, window title, root view IDs). On each phone you open the power menu a few times, from the unlocked screen, the lock screen and the Fold's cover screen. We read the logs and write down that phone's power-menu signature.

Why this comes first: power-menu detection is OEM-specific. Guessing class names from forum posts would mean building on assumptions. The recorder gives us real data from your three phones, and it's a nice lesson in how accessibility events work.

### Phase 1: Shutdown gate (top priority)
- When the detector matches → `performGlobalAction(GLOBAL_ACTION_BACK)` to dismiss the menu, then show the PIN overlay.
- Correct PIN → open a ~10-second "allowed" window and reopen the power menu with `GLOBAL_ACTION_POWER_DIALOG` so you can use it normally.
- Wrong PIN → count it toward the Feature 2 alarm.
- Edge cases to handle: Samsung's side key set to Bixby, the quick-panel power button, the Fold's two displays, and the service being killed (Phase 5).
- The UI states clearly: *"Holding Power + Volume Down still force-restarts the phone. No app can block this."*

### Phase 2: Wrong-attempt alarm
- `DeviceAdminReceiver.onPasswordFailed` → read `getCurrentFailedPasswordAttempts()`. At 3 or more → start `AlarmService`.
- Device admin's *watch-login* policy is still supported; only the password and camera policies were deprecated. I'll check the API 37 reference again before writing this.
- **To verify before coding:** Android 12+ limits starting a foreground service from the background. I need to confirm that a device-admin broadcast or a running accessibility service counts as an allowed exemption. If not, the accessibility service starts the alarm instead. I'll report what I find rather than guess.
- Alarm: `MediaPlayer` on `AudioAttributes.USAGE_ALARM`, looping. A volume listener resets `STREAM_ALARM` to max whenever it's changed. It stops only on correct PIN entry or your real unlock (`onPasswordSucceeded`).

### Phase 3: SMS alert
- **Alert early:** the first SMS goes out at the 3rd wrong attempt, while your SIM is almost certainly still in. A thief usually tries unlocking or powering off before pulling the SIM.
- Message content: Google Maps link built from lat/long, accuracy, timestamp, battery %, and **both IMEIs** (all three phones are dual-SIM, so each has two; see D3).
- `SEND_SMS` is fine for a sideloaded app (Play would restrict it). On dual-SIM, pick the SIM explicitly with `SubscriptionManager`. Prefer the eSIM line where there is one.

**SIM removal / swap** (`SubscriptionManager.OnSubscriptionsChangedListener`, a public API that needs `READ_PHONE_STATE`):
- **Your SIM disappears** → treat as theft: start the alarm, lock the screen, record the location. Save the alert as "pending".
- **A different SIM is inserted** (the thief's, or one they sell it on with) → send the pending alert *from that SIM*. The message includes the new SIM's carrier and, if the SIM exposes it, its number. This is the classic "SIM change alert".
- **Wi-Fi fallback (optional, decide later):** if there's no usable SIM but Wi-Fi connects, send the alert over the internet through a messaging service, e.g. a Telegram bot or ntfy. This isn't our own server, so it stays within scope, but it needs a token stored on the phone.

**eSIM phones (S23 FE, Fold 7):** an eSIM can't be pulled out. That only helps if **your active number is on the eSIM**. A thief's remaining options are deleting the eSIM profile (needs the phone unlocked), airplane mode, or powering off. The power gate (Phase 1) and the airplane-mode lock (Phase 4) cover the last two.

**OnePlus 12R (no eSIM):** relies on early alerting, the SIM-change alert and the Wi-Fi fallback. Turn on OxygenOS's own SIM-removal lock and Google Find Hub as well.

### Phase 4: Connectivity (the realistic version)
Options, best first:
1. SMS works without data, so Feature 3 already covers the core need.
2. Turn on **Samsung "Lock network and security"** (Settings → Lock screen → Secure lock settings). It's built in and stops a locked phone's Wi-Fi/data/airplane mode from being changed. Check whether OxygenOS has the same.
3. **Device owner** provisioning: needs a factory reset or adb with no accounts on the phone. It unlocks IMEI access and lets the app block network and airplane-mode changes, but it's a big step. Separate decision later, not now.

The app checks and reports which of these is active.

### Phase 5: OEM survival onboarding
- Ask to be excluded from battery optimisation (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- **OnePlus:** walk through auto-launch, "Allow background activity", and locking the app in recents.
- **Samsung:** walk through "Never sleeping apps".
- **Sideloaded:** walk through App Info → ⋮ → "Allow restricted settings" before the accessibility toggle is allowed. Also cover background location "Allow all the time".
- A watchdog notification if the accessibility service gets disabled.

## 5. Decisions I need from you

| # | Decision | My recommendation |
|---|---|---|
| D1 | UI toolkit | **Decided 2026-11-04:** plain Views + `Theme.DeviceDefault`, no Compose/AppCompat. |
| D2 | Secure storage | **Decided 2026-11-04:** a **separate TheftGuard PIN** (not the lock-screen PIN), stored as `HMAC-SHA256(salt ‖ PIN)` with a non-exportable Android Keystore key, and app-side retry limiting. Contacts and IMEIs: Keystore AES-256-GCM written by hand, with a round-trip unit test. `allowBackup="false"`. |
| D3 | IMEI in the SMS | **Agreed 2026-11-04:** you type both IMEIs during setup (dial `*#06#`). The app checks each one with the Luhn checksum, stores them encrypted and includes them in alerts. |
| — | Phase 0 first | Build the recorder before the gate, so detection is based on real data |

**Your to-do before Phase 0:** install Android Studio, then on each phone enable Developer options → USB debugging.
