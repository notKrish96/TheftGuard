# Power-menu signatures (Phase 0 recorder results)

> **Since 2026-11-05 TheftGuard uses ONE universal detector for every Android 16+ phone**
> (`gate/PowerMenuDetector.kt`): (1) SystemUI window class containing "GlobalActions",
> (2) known view IDs (Samsung), (3) a focused SYSTEM window showing both a "Power off" and
> a "Restart" label, read in the phone's own language from Android's resources.
> The per-phone notes below are the evidence it was built from.

## Galaxy S23 FE: SM-S711B, Android 16 (SDK 36), One UI 8.5, build S711BXXSJGZI6
Recorded 2026-11-04. Raw log: `s23fe-2026-11-04.log` (gitignored, kept local).

### Unlocked: CONFIRMED (both entry points)
Side + Volume down and the quick-panel power icon open the **same** dialog. Every time, it fired:

```
TYPE_WINDOW_STATE_CHANGED
  pkg = com.android.systemui
  cls = com.samsung.android.globalactions.presentation.view.SamsungGlobalActionsDialogBase$ActionsDialog
  window type = SYSTEM, focused, no title
```

Each opening fires the event twice, about 150–250 ms apart, so the detector must debounce.

View tree (stable resource IDs, good for a second check):
- `com.android.systemui:id/sec_global_actions_item_list` (desc "Power off menu")
  - items: `sec_global_actions_icon` (clickable) + `sec_global_actions_label`
    → "Power off", "Restart", "Emergency call", "Lockdown mode", "Medical info"
- `sec_global_actions_bottom_container` → "Side button settings"
- `sec_global_actions_bottom_force_restart_msg_view` (Samsung's own force-restart hint)

Note: the menu offers **Lockdown mode**. Our gate must not block that, because it helps the owner.

### Locked: CONFIRMED (both entry points), re-run 22:00–22:01
Raw log: `s23fe-2026-11-04-locked.log`. With `locked=true`, Side + Volume down and the lock-screen quick-panel power icon open the **same** `SamsungGlobalActionsDialogBase$ActionsDialog`, with the same IDs and the same five items. The quick panel's power icon **is** reachable from the lock screen, so a thief can use it.

Timing detail: `TYPE_WINDOWS_CHANGED` (a new focused SYSTEM window from SystemUI) arrives about 75 ms **before** the `TYPE_WINDOW_STATE_CHANGED`. A detector that also checks WINDOWS_CHANGED can react slightly faster.

Lock-screen overlay (`android.widget.FrameLayout`, window 12, SystemUI) also fires STATE_CHANGED repeatedly. The detector must **not** match on package alone.

### Built-in check (found 2026-11-04)
One UI 8.5 asks for the lock-screen credential on Power off / Restart **only when locked**. Unlocked: no check.

### Detector rule for S23 FE (to implement in Phase 1)
Match if `pkg == com.android.systemui` **and** (class name contains `GlobalActionsDialog` **or** the window tree contains `com.android.systemui:id/sec_global_actions_item_list`). Ignore repeats within about 500 ms.

## Galaxy Z Fold 7: not recorded yet
## OnePlus 12R: CPH2585, Android 16, OxygenOS 16.0.5
Not formally recorded. On 2026-11-05 the class-name rule ("GlobalActions" in SystemUI) caught the locked power menu (Power + Volume Up) on the first try. It also has a built-in "Require password to power off".
