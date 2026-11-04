package dev.krishgoel.theftguard.gate

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import dev.krishgoel.theftguard.R
import dev.krishgoel.theftguard.security.PinHasher
import dev.krishgoel.theftguard.security.PinStore

/**
 * The full-screen PIN pad that replaces the power menu.
 *
 * Window choices, and why:
 *  - TYPE_ACCESSIBILITY_OVERLAY: only accessibility services may use it. It
 *    draws above the lock screen and SystemUI dialogs, and needs no
 *    "Display over other apps" permission.
 *  - FLAG_NOT_FOCUSABLE: our window never takes key focus. That way the BACK
 *    action we send goes to the power menu (closing it), not to us. Our keypad
 *    is made of buttons, so we don't need a keyboard anyway.
 *  - FLAG_SECURE: blocks screenshots and screen recording of this window, so
 *    a recording can't capture which keys were tapped.
 */
class PinGateOverlay(
    private val service: AccessibilityService,
    private val pinStore: PinStore,
    private val onUnlocked: () -> Unit,
    private val onClosed: () -> Unit,
    /** Told the running count of wrong tries in a row (feeds the Phase 2 alarm). */
    private val onWrongPin: (failures: Int) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val view: View = LayoutInflater
        .from(ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault))
        .inflate(R.layout.overlay_pin_gate, null)
    private val dots = view.findViewById<TextView>(R.id.gateDots)
    private val status = view.findViewById<TextView>(R.id.gateStatus)
    private val message = view.findViewById<TextView>(R.id.gateMessage)
    private val cancel = view.findViewById<Button>(R.id.gateCancel)
    private val entered = StringBuilder()

    var isShowing = false
        private set

    init {
        buildKeypad(view.findViewById(R.id.gateKeypad))
        cancel.setOnClickListener { close() }
        // Content stays clear of the status bar / gesture area even though the window covers them.
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /**
     * Show the PIN pad with [text] as its message. If it's already showing,
     * just switch the message (e.g. the gate screen becomes the alarm screen).
     * [cancelable] = false hides Cancel: closing the alarm screen wouldn't stop
     * the alarm, so there's nothing to cancel.
     */
    fun show(text: String, cancelable: Boolean = true) {
        message.text = text
        cancel.visibility = if (cancelable) View.VISIBLE else View.GONE
        if (isShowing) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            fitInsetsTypes = 0      // cover the whole screen, including the status bar
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, params)
        isShowing = true
        reset("")
    }

    /** Remove the window without unlocking (Cancel, or service shutting down). */
    fun close() {
        if (!isShowing) return
        wm.removeView(view)
        isShowing = false
        entered.setLength(0)
        onClosed()
    }

    private fun buildKeypad(grid: GridLayout) {
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        val size = (80 * service.resources.displayMetrics.density).toInt()
        for (label in keys) {
            val b = Button(grid.context).apply {
                text = label
                textSize = 24f
                setOnClickListener { onKey(label) }
            }
            grid.addView(b, GridLayout.LayoutParams().apply { width = size; height = size })
        }
    }

    private fun onKey(label: String) {
        when (label) {
            "⌫" -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
            "OK" -> { submit(); return }
            else -> if (entered.length < PinHasher.MAX_LENGTH) entered.append(label)
        }
        dots.text = "•".repeat(entered.length)
    }

    private fun submit() {
        val attempt = entered.toString()
        when (val r = pinStore.check(attempt)) {
            PinStore.Result.Ok -> {
                wm.removeView(view)
                isShowing = false
                entered.setLength(0)
                onUnlocked()
            }
            is PinStore.Result.Wrong -> {
                reset(
                    if (r.waitMs > 0) "Wrong PIN. Try again in ${r.waitMs / 1000} s"
                    else "Wrong PIN"
                )
                onWrongPin(r.failures)
            }
            is PinStore.Result.LockedOut -> reset("Too many tries. Wait ${r.remainingMs / 1000 + 1} s")
            PinStore.Result.Unavailable -> reset("Can't check the PIN right now. Try again in a moment.")
        }
    }

    private fun reset(message: String) {
        entered.setLength(0)
        dots.text = ""
        status.text = message
    }
}
