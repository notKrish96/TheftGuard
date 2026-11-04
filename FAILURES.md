# TheftGuard: failures log

Everything that didn't work as planned, why, and what happened to it. Newest first within each phone.
**Status:** 🔴 open · 🟡 worked around · 🟢 fixed

---

## Galaxy S23 FE (SM-S711B, Android 16 / One UI 8.5)

### 🔴 F8: Power-off-attempt warning / forced-restart detection failed in test
- **When:** 2026-11-04, ~23:50, last test of Phase 3a.
- **What was tested:** locked phone → Side + Volume down → expected the "power-off try while locked" SMS right away, then "forced restart detected" + alarm after the reboot.
- **What happened:** Krish reported "something failed". Details not captured yet, because the OnePlus was plugged in before the S23 FE log was read.
- **Next step:** plug the S23 FE back in and read `/data/user_de/0/dev.krishgoel.theftguard/files/recorder.log` (lines starting `ATTEMPT:` and `SMS:`), plus `shared_prefs/power_attempt.xml`.

### 🟡 F7: Force-restart detection from the Volume Down key: impossible on Samsung
- **Idea:** power menu visible + Volume Down still held 2 s later = force restart coming → SMS in the ~5 s before the power cut.
- **What happened:** the detector never fired. A diagnostic build showed Volume Down events arrive when the key is pressed *alone*, but **not when Side + Volume down are held together**: One UI keeps the key for its own button-combo handling before accessibility services see it.
- **Workaround:** replaced with `PowerAttempt` (F8). The key-filter capability was removed from the service, since it was no longer needed.
- **Lesson:** you can't intercept or observe the hardware force-restart combo from an app. The power chip's ~7 s timer is below software entirely.

### 🟢 F6: Log lines lost on a force restart
- **What happened:** the last seconds of the log before each force restart were missing (e.g. live-location ticks).
- **Cause:** file writes sat in the RAM page cache. A power cut drops them.
- **Fix:** `EventRecorder` calls `fd.sync()` after every line. Settings were never affected: they use `commit()`, which syncs.

### 🟢 F5: SMS after a restart failed with error 4 (no service)
- **What happened:** the "restarted during alarm" SMS was sent 0.6 s after boot and failed: `RESULT_ERROR_NO_SERVICE`. The modem wasn't on the network yet.
- **Fix:** every failed SMS is retried every 15 s, up to 20 times (5 min).

### 🟢 F4: Multi-part SMS too slow for the force-restart window
- **What happened:** the first alert was 204 characters = 2 SMS parts. The carrier accepted roughly **one part every ~6.3 s**, so the alert took ~13 s to leave.
- **Fix:** every message fits in ONE SMS (≤ 160 GSM-7 characters). Phone name dropped, wording shortened. The worst case is unit-tested (158 characters).

### 🟢 F3: Gate off until the first unlock after a restart
- **What happened:** after a reboot the gate did nothing until the phone was unlocked once.
- **Cause:** Direct Boot. The service wasn't `directBootAware`, and its settings lived in credential-encrypted storage.
- **Fix:** service + device-admin receiver are `directBootAware`. All data is in device-protected storage (`Storage.kt`), migrated automatically.

### 🟢 F2: Gate slow, sometimes appearing only after Back
- **Cause 1:** the recorder ran before the gate on every event (file writes + tree dumps).
- **Cause 2:** right after boot the phone was overloaded (load average 70), and the detector searched the large lock-screen window on every `WINDOWS_CHANGED`.
- **Fix:** gate runs first; overlay pre-built and shown *before* sending Back; the detector caches its verdict per window ID.

### 🟢 F1: Quick-panel power button worked right after a correct PIN
- **Cause:** the allowance after a correct PIN was "any power menu for 20 s", so every menu in that window got through.
- **Fix:** single-use allowance bound to the one reopened menu window, ending when it closes (Back).

### Not failures (decisions)
- Fingerprint/face unlock does **not** stop the alarm (Krish's decision). Only a PIN does.
- One UI asks for the lock-screen PIN on Power off/Restart **only when locked**. TheftGuard's gate runs in both states (both PINs when locked).

---

## OnePlus 12R (CPH2585, Android 16 / OxygenOS 16.0.5)
_Testing started 2026-11-04._

### 🔴 O2: Log file empty after a force restart
- **When:** 2026-11-05 00:07, force-restart test.
- **What happened:** after boot, `files/recorder.log` didn't exist at all, not even the `service connected` line. Yet the service clearly ran (its overlay window was added at 00:07:42, per WindowManager). OxygenOS also hides TheftGuard's `Log.i` lines from logcat, so there's no second source.
- **Unknown:** whether `onServiceConnected` ran at 00:07:13 or later, and whether the "forced restart detected" path fired.
- **Next step:** reproduce; check whether the log file reappears after any new event; consider writing a tiny separate "boot marker" file.

### 🟡 O1: TheftGuard "slow to start after boot"
- **Measured:** boot start 00:07:02.5 → lock screen 00:07:13.2 → TheftGuard service process started **00:07:13.7** (Direct Boot, before unlock). That's as early as Android allows.
- **Real causes of the felt slowness:** load average 50 at 1 min uptime (OxygenOS starts everything at once); the first SMS after boot fails with "no service" and is retried every 15 s; TheftGuard isn't battery-optimisation-exempt.
- **Mitigation:** Krish sets TheftGuard to Battery → Unrestricted, Auto-launch on, and locks it in recents. (Phase 5 onboarding will walk through this.)

### ✅ Works on the 12R (2026-11-05 00:04, quiet test mode)
Locked power-menu attempt detected (generic detector), warning SMS sent in 1.8 s; 3-wrong-PIN alarm; live location ×3; owner/stop texts. All 14 SMS `sent ✓`, carrier ~1.5 s per SMS.

## Galaxy Z Fold 7
_Not started._
