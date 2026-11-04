package dev.krishgoel.theftguard.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.krishgoel.theftguard.BuildConfig
import dev.krishgoel.theftguard.R
import dev.krishgoel.theftguard.TestMode
import dev.krishgoel.theftguard.admin.TheftGuardAdminReceiver
import dev.krishgoel.theftguard.alarm.AlarmController
import dev.krishgoel.theftguard.alert.AlertSender
import dev.krishgoel.theftguard.alert.AlertSettings
import dev.krishgoel.theftguard.alert.AlertValidation
import dev.krishgoel.theftguard.alert.LocationFetcher
import dev.krishgoel.theftguard.gate.EventRecorder
import dev.krishgoel.theftguard.gate.GateState
import dev.krishgoel.theftguard.gate.PowerGateService
import dev.krishgoel.theftguard.gate.PowerMenuDetector
import dev.krishgoel.theftguard.security.KeystoreMac
import dev.krishgoel.theftguard.security.PinHasher
import dev.krishgoel.theftguard.security.PinStore

/**
 * Main screen: gate status and settings, plus the Phase 0 recorder (debug).
 *
 * Plain android.app.Activity, no AppCompat (decision D1): we only support
 * Android 16+, so there's nothing to backport.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var gateSwitch: Switch
    private lateinit var btnSetPin: Button
    private lateinit var pinStore: PinStore
    private lateinit var btnAdmin: Button
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen shows contacts, IMEIs and PIN dialogs:
        //  - FLAG_SECURE: no screenshots, screen recording, or preview in Recents.
        //  - setHideOverlayWindows: other apps' "draw over other apps" windows are
        //    hidden while we're in front, so they can't trick you into tapping
        //    something ("tapjacking") or cover our dialogs with fake ones.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).filterTouchesWhenObscured = true
        applySystemBarPadding(findViewById(R.id.root))

        pinStore = PinStore(this)
        status = findViewById(R.id.status)
        logView = findViewById(R.id.logView)
        gateSwitch = findViewById(R.id.gateSwitch)
        btnSetPin = findViewById(R.id.btnSetPin)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnSetPin.setOnClickListener { startPinChange() }

        gateSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !pinStore.isSet()) {
                sw.isChecked = false
                toast("Set a TheftGuard PIN first")
                return@setOnCheckedChangeListener
            }
            if (!on && GateState.isEnabled(this)) {
                // Turning protection OFF needs the PIN, or a thief who grabs the
                // phone unlocked could just flip this switch.
                sw.isChecked = true
                confirmPin("Turn off the gate") {
                    GateState.setEnabled(this, false)
                    refresh()
                }
                return@setOnCheckedChangeListener
            }
            GateState.setEnabled(this, on)
            refresh()
        }

        // ── Alarm ──
        btnAdmin = findViewById(R.id.btnAdmin)
        btnAdmin.setOnClickListener {
            // Android's own "Activate device admin app?" screen. The explanation
            // is shown to you there, and only you can tap Activate.
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, TheftGuardAdminReceiver.component(this))
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets TheftGuard notice wrong lock-screen PINs, so it can sound the alarm after 3 in a row.",
                    )
            )
        }
        findViewById<Button>(R.id.btnTestAlarm).setOnClickListener {
            if (!isGateServiceEnabled()) toast("Turn on the accessibility service first: it keeps the alarm alive")
            AlarmController.start(this, "test", autoStopMs = 3_000)
        }

        // ── SMS alert ──
        findViewById<Button>(R.id.btnContacts).setOnClickListener {
            // Changing who gets alerted needs the PIN once contacts exist,
            // or a thief with an unlocked phone could just delete them.
            if (AlertSettings.contacts(this).isNotEmpty() && pinStore.isSet()) {
                confirmPin("Edit emergency contacts") { editContacts() }
            } else {
                editContacts()
            }
        }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestAlertPermissions() }
        findViewById<Button>(R.id.btnTestSms).setOnClickListener {
            if (!AlertSender.isReady(this)) {
                toast("Add a contact and grant SMS permission first")
            } else {
                AlertSender.sendTest(this)
                toast("Test SMS sent to ${AlertSettings.contacts(this).size} contact(s)")
            }
        }

        // ── Debug tools: release builds don't show (or honour) them at all ──
        findViewById<View>(R.id.debugSection).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        // Quiet test mode MUTES THE SIREN, so switching it ON needs the PIN.
        // (Otherwise a thief holding the unlocked phone could silence the alarm.)
        val quietSwitch = findViewById<Switch>(R.id.quietTestSwitch)
        quietSwitch.isChecked = TestMode.isOn(this)
        quietSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !TestMode.isOn(this) && pinStore.isSet()) {
                sw.isChecked = false
                confirmPin("Turn on quiet test mode") { TestMode.set(this, true); sw.isChecked = true; refresh() }
                return@setOnCheckedChangeListener
            }
            TestMode.set(this, on)
            refresh()
        }

        // ── Debug recorder ──
        // The recorder logs on-screen system text, so switching it ON needs the PIN too.
        val recordSwitch = findViewById<Switch>(R.id.recordSwitch)
        recordSwitch.isChecked = EventRecorder.isRecording(this)
        recordSwitch.setOnCheckedChangeListener { sw, on ->
            if (on && !EventRecorder.isRecording(this) && pinStore.isSet()) {
                sw.isChecked = false
                confirmPin("Turn on the recorder") {
                    EventRecorder.setRecording(this, true)
                    EventRecorder.line(this, "=== recording ON ===")
                    sw.isChecked = true
                    refresh()
                }
                return@setOnCheckedChangeListener
            }
            EventRecorder.setRecording(this, on)
            EventRecorder.line(this, if (on) "=== recording ON ===" else "=== recording OFF ===")
            refresh()
        }
        findViewById<Button>(R.id.btnMark).setOnClickListener { askForMarker() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            EventRecorder.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()   // the user may be coming back from Settings, so re-check everything
    }

    private fun refresh() {
        val serviceOn = isGateServiceEnabled()
        val pinSet = pinStore.isSet()
        val gateOn = GateState.isEnabled(this)

        // Setting the switch programmatically fires its listener; that's harmless here
        // because the value is the same one we just read.
        gateSwitch.isChecked = gateOn
        btnSetPin.text = if (pinSet) "Change TheftGuard PIN" else "Set TheftGuard PIN"

        val adminOn = dpm.isAdminActive(TheftGuardAdminReceiver.component(this))
        btnAdmin.isEnabled = !adminOn
        btnAdmin.text = if (adminOn) "Device admin active" else "Activate device admin"

        val protecting = serviceOn && pinSet && gateOn
        status.text = buildString {
            if (TestMode.isOn(this@MainActivity)) appendLine("🧪 QUIET TEST MODE ON: siren muted, turn it off after testing!")
            appendLine(if (protecting) "🛡 Power-off gate ACTIVE" else "⚠ Power-off gate NOT active")
            appendLine("${tick(serviceOn)} Accessibility service")
            appendLine("${tick(pinSet)} TheftGuard PIN set")
            appendLine("${tick(gateOn)} Gate switched on")
            appendLine("${tick(adminOn)} Device admin (wrong-attempt alarm)")
            val contacts = AlertSettings.contacts(this@MainActivity).size
            val imeis = AlertSettings.imeis(this@MainActivity).size
            appendLine("${tick(contacts > 0)} Emergency contacts: $contacts")
            appendLine("${tick(imeis > 0)} IMEIs saved: $imeis")
            appendLine("${tick(AlertSender.hasSmsPermission(this@MainActivity))} SMS permission")
            appendLine("${tick(LocationFetcher.hasBackgroundPermission(this@MainActivity))} Location: Allow all the time")
            appendLine("${tick(LocationFetcher.isLocationOn(this@MainActivity))} Location switched on")
            if (AlarmController.isActive(this@MainActivity)) appendLine("🚨 ALARM ACTIVE: ${AlarmController.reason(this@MainActivity)}")
            appendLine("Detector: ${PowerMenuDetector.forThisDevice().name}")
            if (pinSet) append("PIN key stored in: ${KeystoreMac.securityLevelName()}")
        }
        logView.text = EventRecorder.readTail(this).ifEmpty { "(recorder log is empty)" }
    }

    private fun tick(ok: Boolean) = if (ok) "✅" else "❌"

    // ── SMS alert setup ─────────────────────────────────────────────────────

    private fun editContacts() {
        val current = AlertSettings.contacts(this)
        val contactFields = (0 until AlertSettings.MAX_CONTACTS).map { i ->
            EditText(this).apply {
                hint = "Emergency contact ${i + 1} (with country code, e.g. +91…)"
                inputType = InputType.TYPE_CLASS_PHONE
                setText(current.getOrNull(i) ?: "")
            }
        }
        val imeiFields = (1..2).map { slot ->
            EditText(this).apply {
                hint = "IMEI $slot (dial *#06# to see it)"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(AlertSettings.imei(this@MainActivity, slot))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Emergency contacts & IMEIs")
            .setMessage("Stored encrypted with a hardware key. Use full numbers with country code, since your phones are in India and the UK.")
            .setView(padded(*(contactFields + imeiFields).toTypedArray()))
            .setPositiveButton("Save") { _, _ ->
                val numbers = contactFields.map { it.text.toString().trim() }.filter { it.isNotEmpty() }
                val imeis = imeiFields.map { it.text.toString().filter(Char::isDigit) }
                when {
                    numbers.isEmpty() -> toast("Add at least one contact")
                    numbers.any { !AlertValidation.isValidPhone(it) } -> toast("A phone number doesn't look right")
                    imeis.any { it.isNotEmpty() && !AlertValidation.isValidImei(it) } ->
                        toast("An IMEI is wrong: it must be 15 digits and pass the check digit")
                    else -> {
                        AlertSettings.setContacts(this, numbers)
                        imeis.forEachIndexed { i, v -> AlertSettings.setImei(this, i + 1, v) }
                        toast("Saved")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    /**
     * Android's permission flow, in two steps:
     *  1. SMS, phone state and location ("while using the app") in one prompt.
     *  2. Background location: since Android 11 this can't be a prompt; Android
     *     opens TheftGuard's location settings, where you pick "Allow all the time".
     */
    private fun requestAlertPermissions() {
        val needed = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        when {
            needed.isNotEmpty() -> requestPermissions(needed.toTypedArray(), REQ_FOREGROUND)
            !LocationFetcher.hasBackgroundPermission(this) -> askBackgroundLocation()
            else -> toast("All permissions granted")
        }
    }

    private fun askBackgroundLocation() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage("On the next screen choose \"Allow all the time\". Alerts are sent while TheftGuard isn't open, so it needs location in the background.")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
            .setNegativeButton("Not now", null)
            .showSecure()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_FOREGROUND && LocationFetcher.hasPermission(this) &&
            !LocationFetcher.hasBackgroundPermission(this)
        ) {
            askBackgroundLocation()
        }
        refresh()
    }

    // ── PIN setup ───────────────────────────────────────────────────────────

    /** Changing an existing PIN needs the old one first, so a thief with an unlocked phone can't just reset it. */
    private fun startPinChange() {
        if (!pinStore.isSet()) askNewPin() else confirmPin("Change PIN") { askNewPin() }
    }

    /** Ask for the current PIN; run [onOk] only if it's right. Wrong tries count towards the lockout. */
    private fun confirmPin(title: String, onOk: () -> Unit) {
        val current = pinField("Current TheftGuard PIN")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(current))
            .setPositiveButton("OK") { _, _ ->
                when (val r = pinStore.check(current.text.toString())) {
                    PinStore.Result.Ok -> onOk()
                    is PinStore.Result.Wrong -> {
                        toast("Wrong PIN")
                        // Guessing the PIN inside the app counts towards the alarm too.
                        if (r.failures >= TheftGuardAdminReceiver.ALARM_AFTER) {
                            AlarmController.start(this, "${r.failures} wrong TheftGuard PINs")
                        }
                    }
                    is PinStore.Result.LockedOut -> toast("Too many tries. Wait ${r.remainingMs / 1000 + 1} s")
                    PinStore.Result.Unavailable -> toast("Can't check the PIN right now. Try again.")
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    private fun askNewPin() {
        val first = pinField("New PIN (6–12 digits)")
        val second = pinField("Repeat new PIN")
        AlertDialog.Builder(this)
            .setTitle("Set TheftGuard PIN")
            .setMessage("Use a PIN that is different from your lock-screen PIN, so someone who watched you unlock still can't power the phone off.")
            .setView(padded(first, second))
            .setPositiveButton("Save") { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    !PinHasher.isValidFormat(a) -> toast("PIN must be 6–12 digits")
                    a != b -> toast("The two PINs don't match")
                    else -> {
                        pinStore.setPin(a)
                        toast("PIN saved")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun padded(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        views.forEach { addView(it) }
    }

    // ── Recorder helpers ────────────────────────────────────────────────────

    /** A marker is a labelled line in the log, so we can tell test runs apart afterwards. */
    private fun askForMarker() {
        val input = EditText(this).apply { hint = "e.g. unlocked · hold side key" }
        AlertDialog.Builder(this)
            .setTitle("Marker for the next test")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                EventRecorder.line(this, "##### MARKER: ${input.text.toString().ifBlank { "(no label)" }} #####")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .showSecure()
    }

    /**
     * Android keeps the list of enabled accessibility services in a secure
     * setting as "package/class:package/class". Any app may read it.
     */
    private fun isGateServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val ours = ComponentName(this, PowerGateService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
    }

    private companion object {
        const val REQ_FOREGROUND = 1
        const val REQ_BACKGROUND = 2
    }

    /** Dialogs are separate windows: give each one FLAG_SECURE too. */
    private fun AlertDialog.Builder.showSecure(): AlertDialog =
        create().also {
            it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            it.show()
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Apps targeting Android 15+ draw edge-to-edge, behind the status and
     * navigation bars. Pad the content by the bar sizes so nothing hides under them.
     */
    private fun applySystemBarPadding(root: View) {
        val base = (16 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }
}
