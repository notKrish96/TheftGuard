# TheftGuard

A personal anti-theft app for Android 16+, written in Kotlin with zero runtime dependencies.

If someone steals the phone, TheftGuard makes it hard to switch off, sounds a loud alarm, and texts your emergency contacts the phone's location and IMEIs.

## Features

| | What it does |
|---|---|
| **Power-off gate** | The power menu (side key or quick panel, locked or unlocked) is covered by a PIN pad. Only the TheftGuard PIN reopens it. |
| **Wrong-attempt alarm** | 3 wrong lock-screen PINs (or 3 wrong TheftGuard PINs) start a looping siren at forced maximum volume, through the speaker, with vibration. Survives a force restart. Only a PIN stops it. |
| **SMS alert + live location** | Texts your contacts the reason, a Google Maps link, both IMEIs and battery level, then live location every 3 minutes while the alarm runs. Works without mobile data. |
| **Force-restart warning** | A power-off attempt on a locked phone is texted immediately; if the phone is then force-restarted, the next boot detects it and starts the alarm. |
| **Tamper alerts** | Switching off the accessibility service or device admin texts your contacts. |

Power-menu detection is universal: SystemUI's `GlobalActions` window class, or a system window showing both "Power off" and "Restart", read in the phone's own language. Tested on a Galaxy S23 FE (One UI 8.5) and a OnePlus 12R (OxygenOS 16).

## What an app can't do

Being upfront about it (details in [SECURITY.md](SECURITY.md)):

- A **hardware force restart** (holding the power keys for 7–10 s) can't be blocked by any app. TheftGuard warns before it and recovers after it instead.
- Normal apps can't switch **mobile data, Wi-Fi or airplane mode** on. SMS alerts need only a SIM with signal.
- Normal apps can't read the **IMEI** on Android 10+, so you enter it once during setup (`*#06#`).

## Build

```bash
./gradlew assembleDebug        # test build (debug tools on)
./gradlew assembleRelease      # what you carry: not debuggable, debug tools compiled out
```

Requirements: Android SDK with platform 37, JDK 17+.

## Docs

- [PLAN.md](PLAN.md): design and build phases
- [SECURITY.md](SECURITY.md): threat model, review findings, residual risks
- [recordings/FINDINGS.md](recordings/FINDINGS.md): power-menu signatures per phone
