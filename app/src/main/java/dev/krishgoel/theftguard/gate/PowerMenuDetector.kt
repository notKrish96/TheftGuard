package dev.krishgoel.theftguard.gate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * "Is the system power menu on screen right now, and which window is it?"
 *
 * We return the menu's window ID (not just true/false) so the gate can tell
 * "the same menu firing another event" apart from "a NEW power menu".
 */
interface PowerMenuDetector {
    /** Name shown on the status screen, so you know which rule is active. */
    val name: String

    /** If this event means the power menu is showing, its window ID; otherwise null. */
    fun menuWindowId(event: AccessibilityEvent, service: AccessibilityService): Int?

    /** Window ID of the power menu if it's on screen now, else null. Used to retry dismissing it. */
    fun findMenuOnScreen(service: AccessibilityService): Int?

    companion object {
        /** One detector for every phone (Android 16+). */
        fun forThisDevice(): PowerMenuDetector = UniversalPowerMenuDetector
    }
}

/**
 * Works on any Android 16+ phone, not tied to one manufacturer.
 *
 * Three signals, any one is enough. Cheapest first:
 *
 *  1. Class name. A STATE_CHANGED event from SystemUI whose window class
 *     contains "GlobalActions". That's the internal name of the power menu in
 *     stock Android ("GlobalActionsDialogLite"), Samsung
 *     ("SamsungGlobalActionsDialogBase"), and OnePlus/OxygenOS (verified on
 *     the S23 FE and 12R, 2026-11-04). Free: no extra lookups.
 *
 *  2. Known view ID. Samsung's "sec_global_actions_item_list". One fast lookup.
 *
 *  3. Labels, the universal one. A focused SYSTEM window (never an app)
 *     containing a "Power off" button AND a "Restart" button.
 *     The labels are read from Android's own resources in the phone's
 *     CURRENT language, so it works in Hindi, German, etc., plus English
 *     fallbacks. Covers phone makers we've never tested.
 *
 * Why SYSTEM windows only: an app could show buttons called "Power off" and
 * "Restart"; that's not the real menu. Only the system can draw TYPE_SYSTEM
 * windows.
 *
 * Speed: verdicts are cached per window ID (power menus open as a NEW window
 * each time), and brand-new windows get re-checked for SETTLE_MS because
 * their buttons may not be filled in on the first look.
 */
object UniversalPowerMenuDetector : PowerMenuDetector {
    override val name = "Universal (any Android 16+): class name + labels"

    private const val SYSTEMUI = "com.android.systemui"
    private const val CLASS_HINT = "GlobalActions"
    private val KNOWN_IDS = listOf(
        "com.android.systemui:id/sec_global_actions_item_list",   // Samsung One UI
    )
    private const val SETTLE_MS = 400L
    private const val MAX_NODES = 250

    private val verdicts = HashMap<Int, Boolean>()
    private val firstSeen = HashMap<Int, Long>()

    override fun menuWindowId(event: AccessibilityEvent, service: AccessibilityService): Int? =
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg == SYSTEMUI && event.className?.contains(CLASS_HINT, ignoreCase = true) == true) {
                    verdicts[event.windowId] = true                  // signal 1
                    event.windowId
                } else if (pkg == SYSTEMUI || pkg == "android") {
                    findMenuOnScreen(service)                        // signals 2 + 3
                } else null
            }
            // Arrives earlier than STATE_CHANGED: our fastest chance.
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> findMenuOnScreen(service)
            else -> null
        }

    /**
     * Signals 2 and 3 need to read other windows. In minimal-access mode
     * (canRetrieveWindowContent=false, see power_gate_service.xml) we skip
     * them and rely on signal 1, the class name.
     */
    fun canReadWindows(service: AccessibilityService): Boolean =
        (service.serviceInfo?.capabilities ?: 0) and
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0

    override fun findMenuOnScreen(service: AccessibilityService): Int? {
        if (!canReadWindows(service)) return null
        if (verdicts.size > 256) { verdicts.clear(); firstSeen.clear() }
        val now = SystemClock.uptimeMillis()
        val byDisplay = service.windowsOnAllDisplays          // Fold: both screens
        for (i in 0 until byDisplay.size()) {
            for (w in byDisplay.valueAt(i)) {
                if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || !w.isFocused) continue
                verdicts[w.id]?.let { isMenu -> if (isMenu) return w.id else continue }

                val root = w.root ?: continue
                val isMenu = hasKnownId(root) || hasPowerLabels(service, root)
                if (isMenu) {
                    verdicts[w.id] = true
                    return w.id
                }
                val seen = firstSeen.getOrPut(w.id) { now }
                if (now - seen > SETTLE_MS) verdicts[w.id] = false
            }
        }
        return null
    }

    private fun hasKnownId(root: AccessibilityNodeInfo): Boolean =
        KNOWN_IDS.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }

    /** Breadth-first walk; stops as soon as the decision is clear. */
    private fun hasPowerLabels(ctx: Context, root: AccessibilityNodeInfo): Boolean {
        val labels = labels(ctx)
        val found = HashSet<PowerLabels.Kind>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.text?.let { t -> PowerLabels.kindOf(t, labels)?.let(found::add) }
            node.contentDescription?.let { d -> PowerLabels.kindOf(d, labels)?.let(found::add) }
            if (PowerLabels.looksLikePowerMenu(found)) return true
            for (c in 0 until node.childCount) node.getChild(c)?.let(queue::add)
        }
        return false
    }

    // ── labels in the phone's current language ──

    private var labelsLocale: Locale? = null
    private var cachedLabels: Map<PowerLabels.Kind, Set<String>> = PowerLabels.ENGLISH

    /** Android's translated labels + English fallbacks. Rebuilt if the language changes. */
    @Synchronized
    fun labels(ctx: Context): Map<PowerLabels.Kind, Set<String>> {
        val locale = ctx.resources.configuration.locales[0]
        if (locale == labelsLocale) return cachedLabels

        val sources = listOfNotNull(
            "android" to Resources.getSystem(),
            try {
                SYSTEMUI to ctx.packageManager.getResourcesForApplication(SYSTEMUI)
            } catch (e: Exception) {
                null
            },
        )
        cachedLabels = PowerLabels.Kind.entries.associateWith { kind ->
            val set = PowerLabels.ENGLISH.getValue(kind).toMutableSet()
            for ((pkg, res) in sources) {
                for (name in PowerLabels.RESOURCE_NAMES.getValue(kind)) {
                    val id = res.getIdentifier(name, "string", pkg)
                    if (id != 0) runCatching { set += PowerLabels.normalize(res.getString(id)) }
                }
            }
            set
        }
        labelsLocale = locale
        return cachedLabels
    }
}
