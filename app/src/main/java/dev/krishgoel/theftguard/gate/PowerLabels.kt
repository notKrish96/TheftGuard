package dev.krishgoel.theftguard.gate

/**
 * Pure label logic for the universal power-menu detector (unit-tested on the PC).
 *
 * Idea: whatever the phone maker, a power menu shows a "Power off" button,
 * plus "Restart" and/or "Emergency". Finding those labels together in a
 * system window is a strong, brand-independent sign that it's the power menu.
 */
object PowerLabels {

    enum class Kind { POWER_OFF, RESTART, EMERGENCY }

    /** English fallbacks, used on top of the phone's own translated labels. */
    val ENGLISH: Map<Kind, Set<String>> = mapOf(
        Kind.POWER_OFF to setOf("power off", "power-off", "shut down", "shutdown", "switch off", "turn off"),
        Kind.RESTART to setOf("restart", "reboot"),
        Kind.EMERGENCY to setOf("emergency", "emergency call", "emergency sos", "emergency mode"),
    )

    /**
     * Android's own resource names for these labels. The framework ("android")
     * has them, and many SystemUIs reuse or copy them. Resolved at runtime in
     * the phone's current language (UniversalPowerMenuDetector.labels()).
     */
    val RESOURCE_NAMES: Map<Kind, List<String>> = mapOf(
        Kind.POWER_OFF to listOf("global_action_power_off", "global_action_shutdown"),
        Kind.RESTART to listOf("global_action_restart", "global_action_reboot"),
        Kind.EMERGENCY to listOf("global_action_emergency", "global_action_emergency_call"),
    )

    /**
     * "Power off," → "power off". Samsung's screen-reader text has a trailing
     * comma; others add full stops or extra spaces.
     */
    fun normalize(s: CharSequence): String =
        s.toString().lowercase().trim().trimEnd(',', '.', ';', ':').trim().replace(Regex("\\s+"), " ")

    /** Which kind of power-menu label is this text, if any? */
    fun kindOf(text: CharSequence, labels: Map<Kind, Set<String>>): Kind? {
        val n = normalize(text)
        if (n.isEmpty() || n.length > 40) return null      // long texts are sentences, not buttons
        return labels.entries.firstOrNull { (_, set) -> n in set }?.key
    }

    /**
     * The decision: a Power-off label AND a Restart label, together in one
     * system window. Every power menu has both; nothing else does.
     *
     * Not enough on its own:
     *  - a lone "Restart" (a system-update notification),
     *  - a lone "Turn off" (a Wi-Fi tile),
     *  - "Power off" + "Emergency call": on a locked Samsung the quick panel's
     *    power icon and the lock screen's Emergency button can share one window.
     * EMERGENCY is still collected (for logs), but it doesn't count.
     */
    fun looksLikePowerMenu(found: Set<Kind>): Boolean =
        Kind.POWER_OFF in found && Kind.RESTART in found
}
