package dev.krishgoel.theftguard.gate

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.krishgoel.theftguard.admin.TheftGuardAdminReceiver
import dev.krishgoel.theftguard.alarm.AlarmController
import dev.krishgoel.theftguard.alert.AlertSender
import dev.krishgoel.theftguard.security.PinStore

/**
 * The shutdown gate (Phase 1).
 *
 * Flow:
 *   power menu appears ──► detector matches ──► allowed? ── yes ──► do nothing
 *                                                  │
 *                                                  no
 *                                                  ▼
 *             BACK (closes the menu) + close the notification shade
 *             show the PIN overlay on top
 *             300 ms later: menu somehow still there? BACK again
 *
 *   right PIN ──► reopen the power menu for you ──► it stays usable until it
 *                 closes (Back); the next one needs the PIN again (see GateState)
 *
 * What this CANNOT stop (and the app says so on its main screen):
 *   Holding Side + Volume down for ~7 s force-restarts the phone in firmware,
 *   below Android. No app can intercept that.
 *
 * Phase 2: this service also hosts the alarm's PIN screen. The same overlay
 * is reused: "gate" mode (power menu) or "alarm" mode (stop the siren).
 * Three wrong TheftGuard PINs in a row also start the alarm.
 *
 * Phase 3: force-restart warning. Holding Side + Volume down for ~7 s
 * restarts the phone in hardware, and nothing can stop that. But the power
 * menu pops up after ~1 s of that hold. A power menu on a LOCKED phone =
 * text the contacts right away (~6 s head start), and remember it. If the
 * phone then reboots without a correct PIN in between, the next boot knows
 * it was force-restarted and starts the alarm (see PowerAttempt).
 * (A first version watched for Volume Down being held, but Samsung keeps
 * that key to itself during the Side + Volume down combo.)
 *
 * The Phase 0 recorder is still here behind its switch, for recording the
 * Fold 7 and OnePlus later. It is off unless you turn it on.
 */
class PowerGateService : AccessibilityService() {

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var detector: PowerMenuDetector
    private lateinit var pinStore: PinStore
    private lateinit var overlay: PinGateOverlay

    /** True while the overlay is the alarm's PIN screen rather than the gate's. */
    private var alarmScreen = false

    /** windowId -> short signature of what we last dumped (recorder only). */
    private val lastDumped = HashMap<Int, String>()

    override fun onServiceConnected() {
        detector = PowerMenuDetector.forThisDevice()
        pinStore = PinStore(this)
        // Build the PIN pad once, now, so showing it later costs only an addView.
        overlay = PinGateOverlay(
            this, pinStore,
            onUnlocked = ::onCorrectPin,
            onClosed = {},
            onWrongPin = ::onWrongPin,
        )
        AlarmController.listener = ::onAlarmChanged
        EventRecorder.line(
            this,
            "=== service connected: ${Build.MANUFACTURER} ${Build.MODEL} " +
                "(${Build.DEVICE}) sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} " +
                "detector=${detector.name} ==="
        )
        // Forced restart while the alarm was sounding? Start it again.
        AlarmController.resumeIfActive(this)
        // Or force-restarted right after a power-off attempt on the locked phone?
        if (PowerAttempt.consumeForcedRestart(this) && !AlarmController.isActive(this)) {
            EventRecorder.line(this, "ATTEMPT: this boot followed a power-off attempt: forced restart")
            AlarmController.start(this, REASON_FORCED)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The gate runs FIRST. Speed is the whole point: every millisecond the
        // real menu is touchable is a chance to tap Power off. The recorder
        // (slow: file writes, tree dumps) only runs afterwards.
        var blockedAt: Int? = null
        if (::detector.isInitialized) {
            val menuId = detector.menuWindowId(event, this)
            blockedAt = gate(event, menuId)
            // After the gate (speed first), and after it may have claimed the
            // window as "yours", check for a locked power-off attempt.
            if (menuId != null) onPowerMenuSeen(menuId)
        }
        if (EventRecorder.isRecording(this)) {
            if (blockedAt != null) EventRecorder.line(this, ">>> GATE: blocked menu window $blockedAt")
            record(event)
        }
    }

    /** Returns the window ID of a menu it blocked, or null. */
    private fun gate(event: AccessibilityEvent, menuId: Int?): Int? {
        if (!GateState.isEnabled(this) || !pinStore.isSet()) return null

        // Did the menu you unlocked just close (you pressed Back)? Then the
        // allowance ends right now, and the next power menu needs the PIN again.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            GateState.claimedWindowId()?.let { if (!isWindowOnScreen(it)) GateState.revoke() }
        }

        val windowId = menuId ?: return null
        if (GateState.isAllowed(windowId)) return null      // the menu we reopened after your PIN

        // One menu opening fires 2–4 matching events (STATE_CHANGED twice, plus
        // WINDOWS_CHANGED), all with the same window ID. React once per window.
        // Extra BACKs would hit whatever is underneath and could navigate an
        // open app backwards. A NEW menu window is always handled immediately.
        val now = SystemClock.elapsedRealtime()
        if (windowId == lastBlockedWindowId && now - lastBlockAt < DEBOUNCE_MS) return null
        lastBlockedWindowId = windowId
        lastBlockAt = now

        blockPowerMenu()
        return windowId
    }

    private var lastBlockedWindowId = Int.MIN_VALUE
    private var lastBlockAt = 0L

    private fun blockPowerMenu() {
        // 1. Cover the screen FIRST. Our overlay sits above the power menu, so
        //    from this moment touches land on our PIN pad, not on "Power off".
        //    It's built in advance (onServiceConnected), so this is just one addView.
        if (!alarmScreen) overlay.show(GATE_MESSAGE)

        // 2. Then close the menu underneath. Our overlay never takes focus, so
        //    BACK still goes to the menu.
        performGlobalAction(GLOBAL_ACTION_BACK)
        // If the menu came from the quick panel, close the panel too.
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)

        // 3. Safety net: if BACK got lost in a race, try again (twice).
        for (delay in RETRY_DELAYS_MS) {
            handler.postDelayed({
                val stillThere = detector.findMenuOnScreen(this)
                if (stillThere != null && !GateState.isAllowed(stillThere)) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, delay)
        }
    }

    // ──────────────── locked power-off attempts (force-restart warning) ────────────────

    private var lastAttemptWindowId = Int.MIN_VALUE

    /**
     * A power menu appeared. If the phone is LOCKED and it isn't the menu we
     * reopened after your PIN, that's someone trying to power off a phone they
     * can't unlock, usually a thief's first move, and the first second of a
     * force restart. Text the contacts now and remember the attempt
     * (see PowerAttempt for the full reasoning).
     */
    private fun onPowerMenuSeen(windowId: Int) {
        if (windowId == lastAttemptWindowId) return              // same menu, another event
        if (windowId == GateState.claimedWindowId()) return      // you just entered the PIN
        if (!keyguard.isKeyguardLocked) return
        lastAttemptWindowId = windowId
        val canAlert = AlertSender.isReady(this)
        PowerAttempt.record(this, alerted = canAlert)            // on disk before anything else
        EventRecorder.line(this, "ATTEMPT: power menu opened on the locked phone (window $windowId)")
        if (canAlert) AlertSender.alert(this, REASON_ATTEMPT)
    }

    /** Right PIN after an attempt: it was you. Clear it, and tell the contacts if they were alerted. */
    private fun clearAttemptAsOwner() {
        if (PowerAttempt.wasAlerted(this)) AlertSender.ownerConfirmed(this)
        PowerAttempt.clear(this)
    }

    private fun isWindowOnScreen(windowId: Int): Boolean {
        val byDisplay = windowsOnAllDisplays
        for (i in 0 until byDisplay.size()) {
            if (byDisplay.valueAt(i).any { it.id == windowId }) return true
        }
        return false
    }

    private fun onCorrectPin() {
        clearAttemptAsOwner()
        if (alarmScreen) {
            // The PIN stops the alarm. It doesn't also open the power menu.
            AlarmController.stop(this)
            return
        }
        GateState.grantOneMenu()
        // Give the overlay a moment to disappear, then reopen the real power menu.
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }, 150)
    }

    private fun onWrongPin(failures: Int) {
        if (failures >= TheftGuardAdminReceiver.ALARM_AFTER) {
            AlarmController.start(this, "$failures wrong TheftGuard PINs")
        }
    }

    private fun onAlarmChanged(active: Boolean) {
        if (active) {
            alarmScreen = true
            // Without a TheftGuard PIN set, only unlocking the phone stops it.
            if (pinStore.isSet()) overlay.show(ALARM_MESSAGE, cancelable = false)
        } else {
            alarmScreen = false
            overlay.close()
        }
    }

    override fun onInterrupt() {
        // Called when the system wants us to stop feedback (speech etc.). We give none.
    }

    /**
     * Called when the accessibility service is switched OFF (Settings →
     * Accessibility). Without it there's no gate and no alarm screen, so tell
     * the contacts. If it was you, ignore the text. (A thief can only reach
     * that setting on an UNLOCKED phone.)
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        EventRecorder.line(this, "TAMPER: accessibility service switched off")
        AlertSender.alert(this, REASON_SERVICE_OFF)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) {
            overlay.close()
            AlarmController.listener = null
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ───────────────────────── Phase 0 recorder (debug) ─────────────────────────

    private fun record(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: "-"
        val cls = event.className?.toString() ?: "-"
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        EventRecorder.line(
            this,
            "EVT $type pkg=$pkg cls=$cls win=${event.windowId} disp=${event.displayId} " +
                "locked=${keyguard.isKeyguardLocked} winChanges=${event.windowChanges}"
        )
        if (isSystemSurface(pkg, cls)) {
            if (event.text.isNotEmpty()) {
                EventRecorder.line(this, "    eventText=\"${shorten(event.text.joinToString(" | "))}\"")
            }
            dumpWindows()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            dumpWindows()
        }
    }

    private fun dumpWindows() {
        val byDisplay = windowsOnAllDisplays
        for (i in 0 until byDisplay.size()) {
            val displayId = byDisplay.keyAt(i)
            for (w in byDisplay.valueAt(i)) {
                val root = w.root
                val rootPkg = root?.packageName?.toString() ?: "-"
                val title = w.title?.toString() ?: "-"
                EventRecorder.line(
                    this,
                    "  WIN disp=$displayId id=${w.id} type=${windowTypeName(w.type)} " +
                        "layer=${w.layer} focused=${w.isFocused} title=\"$title\" pkg=$rootPkg"
                )
                if (root == null || !isSystemSurface(rootPkg, root.className?.toString() ?: "")) continue
                if (title.startsWith("StatusBar") || title.startsWith("NavigationBar")) continue

                val signature = "$title|${root.className}|${root.childCount}|${countNodes(root, 3)}"
                if (lastDumped[w.id] == signature) continue
                lastDumped[w.id] = signature

                EventRecorder.line(this, "    --- tree of window ${w.id} \"$title\" ---")
                dumpTree(root, depth = 0, budget = intArrayOf(MAX_NODES_PER_WINDOW))
            }
        }
    }

    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int, budget: IntArray) {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return
        budget[0]--
        val indent = "    " + "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName ?: "-"
        val text = node.text?.let { shorten(it) } ?: ""
        val desc = node.contentDescription?.let { shorten(it) } ?: ""
        EventRecorder.line(this, "$indent$cls id=$id text=\"$text\" desc=\"$desc\" click=${node.isClickable}")
        for (c in 0 until node.childCount) {
            node.getChild(c)?.let { dumpTree(it, depth + 1, budget) }
        }
    }

    private fun countNodes(node: AccessibilityNodeInfo, depth: Int): Int {
        if (depth == 0) return 1
        var n = 1
        for (c in 0 until node.childCount) node.getChild(c)?.let { n += countNodes(it, depth - 1) }
        return n
    }

    private fun isSystemSurface(pkg: String, cls: String): Boolean =
        pkg in SYSTEM_PACKAGES ||
            cls.contains("GlobalAction", ignoreCase = true) ||
            cls.contains("Power", ignoreCase = true)

    private fun shorten(s: CharSequence): String =
        s.toString().replace('\n', ' ').let { if (it.length > 40) it.take(40) + "…" else it }

    private fun windowTypeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFIER"
        else -> "TYPE_$t"
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(120L, 350L)
        const val DEBOUNCE_MS = 1_000L
        // Short on purpose: each alert must fit one SMS (see AlertMessage).
        const val REASON_ATTEMPT = "power-off try while locked"
        const val REASON_FORCED = "forced restart detected"
        const val REASON_SERVICE_OFF = "protection switched off"
        const val GATE_MESSAGE = "Enter your TheftGuard PIN to power off or restart"
        const val ALARM_MESSAGE = "This phone is protected. Enter the TheftGuard PIN to stop the alarm."
        val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")
        const val MAX_NODES_PER_WINDOW = 80
        const val MAX_DEPTH = 12
    }
}
