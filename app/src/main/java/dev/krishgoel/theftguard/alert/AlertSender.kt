package dev.krishgoel.theftguard.alert

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import dev.krishgoel.theftguard.TestMode
import dev.krishgoel.theftguard.alarm.AlarmController
import dev.krishgoel.theftguard.gate.EventRecorder

/**
 * Sends the SMS alerts (Phase 3).
 *
 *  alert(reason)
 *    1. SMS #1 straight away: reason, device, both IMEIs, LAST KNOWN location,
 *       battery. Speed first: the thief may switch the phone off any moment.
 *    2. SMS #2 once a fresh GPS fix arrives (≤ 45 s), if it's meaningfully
 *       better or the phone moved.
 *    3. Live location: every 3 min while the alarm is active, a new fix is
 *       taken. It's texted only if the phone moved ≥ 50 m or 15 min have
 *       passed, so a phone lying still doesn't burn through SMS credit.
 *
 * SMS goes over the cellular network, not mobile data: it works with data
 * and Wi-Fi off, as long as there's a SIM with signal (and the SIM isn't
 * PIN-locked after a restart).
 */
object AlertSender {
    private const val ACTION_SENT = "dev.krishgoel.theftguard.SMS_SENT"
    private const val FIRST_FIX_TIMEOUT_MS = 45_000L
    private const val LIVE_INTERVAL_MS = 3 * 60_000L
    private const val TEST_INTERVAL_MS = 4_000L       // quiet test mode
    private const val TEST_MAX_UPDATES = 3
    private const val LIVE_FIX_TIMEOUT_MS = 60_000L
    private const val MIN_MOVE_M = 50f
    private const val MAX_SILENCE_MS = 15 * 60_000L
    private const val SAME_REASON_COOLDOWN_MS = 60_000L
    private const val RETRY_DELAY_MS = 15_000L
    private const val MAX_ATTEMPTS = 20                 // 20 × 15 s = 5 min
    private const val EXTRA_ID = "id"
    private const val EXTRA_PART = "part"
    private const val TRACKING_WAKELOCK_MS = 3 * 60 * 60_000L    // safety cap: 3 h

    private val main = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var requestCode = 0

    private var lastReason = ""
    private var lastReasonAt = 0L
    private var lastSentFix: Fix? = null
    private var lastUpdateAt = 0L
    private var tracking = false
    private var alerted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var appCtx: Context? = null
    private var testUpdatesSent = 0

    /**
     * What each in-flight SMS was (number, text, attempt), keyed by an ID.
     * Only the ID travels in the "sent" PendingIntent. PendingIntents are held
     * by the system and show up in system dumps, so the phone number and the
     * message (location, IMEIs) never go in them.
     */
    private data class Outgoing(val number: String, val text: String, val attempt: Int)
    private val outgoing = HashMap<Int, Outgoing>()
    private var nextId = 0

    private fun interval(app: Context) = if (TestMode.isOn(app)) TEST_INTERVAL_MS else LIVE_INTERVAL_MS

    fun hasSmsPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun isReady(ctx: Context): Boolean = hasSmsPermission(ctx) && AlertSettings.contacts(ctx).isNotEmpty()

    /** The main entry point: something bad is happening, tell the contacts. */
    fun alert(ctx: Context, reason: String) {
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        if (!isReady(app)) {
            EventRecorder.line(app, "SMS: not sent ($reason): no SMS permission or no contacts")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (reason == lastReason && now - lastReasonAt < SAME_REASON_COOLDOWN_MS) return
        lastReason = reason
        lastReasonAt = now
        alerted = true

        // 1. Immediately, with whatever location we already have.
        val quick = LocationFetcher.lastKnown(app)
        send(app, AlertMessage.alert(reason, AlertSettings.imeis(app), quick, battery(app)))
        lastSentFix = quick
        lastUpdateAt = now

        // 2. Follow-up with a fresh fix.
        LocationFetcher.current(app, FIRST_FIX_TIMEOUT_MS) { fix -> maybeSendUpdate(app, fix, force = quick == null) }

        // 3. Live location while the alarm is on.
        startTracking(app)
    }

    /** "Send test SMS" button. */
    fun sendTest(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (!isReady(app)) return
        val text = AlertMessage.alert(
            "test, please ignore", AlertSettings.imeis(app), LocationFetcher.lastKnown(app), battery(app),
        )
        send(app, text)
    }

    /**
     * A power-off attempt was alerted, but then the right PIN was entered:
     * it was the owner. Say so, and stop live location unless the alarm is on.
     */
    fun ownerConfirmed(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        if (!AlarmController.isActive(app)) {
            stopTracking()
            alerted = false
            lastReason = ""
        }
        if (isReady(app)) send(app, AlertMessage.ownerOk())
    }

    /** Alarm stopped with the right PIN: end live location and tell the contacts. */
    fun onAlarmStopped(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        stopTracking()
        if (alerted && isReady(app)) send(app, AlertMessage.stopped())
        alerted = false
        lastReason = ""
    }

    // ── live location ───────────────────────────────────────────────────────

    private fun startTracking(app: Context) {
        if (tracking) return
        tracking = true
        testUpdatesSent = 0
        // Keeps the CPU awake between updates even if the screen is off and the
        // phone goes into Doze (the siren usually does this anyway). Capped at 3 h.
        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TheftGuard:live-location")
            .apply { acquire(TRACKING_WAKELOCK_MS) }
        EventRecorder.line(app, "LIVE: tracking started (every ${interval(app) / 1000} s)")
        main.postDelayed(liveTick, interval(app))
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        main.removeCallbacks(liveTick)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        appCtx?.let { EventRecorder.line(it, "LIVE: tracking stopped") }
    }

    private val liveTick = object : Runnable {
        override fun run() {
            val app = appCtx ?: return
            if (!AlarmController.isActive(app)) {
                stopTracking()
                return
            }
            val test = TestMode.isOn(app)
            if (test && testUpdatesSent >= TEST_MAX_UPDATES) {
                EventRecorder.line(app, "LIVE: test mode, $TEST_MAX_UPDATES updates sent, stopping")
                stopTracking()
                return
            }
            // In test mode use the instant last-known fix: a GPS fix can take longer than 4 s.
            if (test) {
                testUpdatesSent++
                val quick = LocationFetcher.lastKnown(app)
                if (quick != null) {
                    maybeSendUpdate(app, quick, force = true)
                } else {
                    // Nothing remembered (e.g. just after a restart): get a fresh fix.
                    LocationFetcher.current(app, LIVE_FIX_TIMEOUT_MS) { fix -> maybeSendUpdate(app, fix, force = true) }
                }
            } else {
                LocationFetcher.current(app, LIVE_FIX_TIMEOUT_MS) { fix -> maybeSendUpdate(app, fix, force = false) }
            }
            main.postDelayed(this, interval(app))
        }
    }

    private fun maybeSendUpdate(app: Context, fix: Fix?, force: Boolean) {
        if (fix == null) {
            EventRecorder.line(app, "LIVE: no fresh fix (location off or no signal)")
            return
        }
        val prev = lastSentFix
        val now = SystemClock.elapsedRealtime()
        val worthIt = force || prev == null ||
            LocationFetcher.distanceM(prev, fix) >= MIN_MOVE_M ||
            fix.accuracyM < prev.accuracyM / 2 ||               // much more precise than before
            now - lastUpdateAt >= MAX_SILENCE_MS
        if (!worthIt) return
        send(app, AlertMessage.update(fix, battery(app)))
        lastSentFix = fix
        lastUpdateAt = now
    }

    // ── sending ─────────────────────────────────────────────────────────────

    private fun send(app: Context, text: String) {
        val full = if (TestMode.isOn(app)) "[T] $text" else text
        for (number in AlertSettings.contacts(app)) sendTo(app, number, full, attempt = 1)
    }

    /**
     * Send one text to one number. If the phone reports it didn't go out (see
     * the receiver below), it's tried again every RETRY_DELAY_MS, up to
     * MAX_ATTEMPTS times. Found in testing: right after a restart the modem
     * isn't on the network yet and every text failed with error 4 ("no service").
     */
    private fun sendTo(app: Context, number: String, text: String, attempt: Int) {
        ensureSentReceiver(app)
        val sms = smsManager(app)
        val parts = sms.divideMessage(text)
        val id = nextId++
        outgoing[id] = Outgoing(number, text, attempt)
        // One "sent" callback per part, so the log shows whether each really left the phone.
        val sentIntents = ArrayList(parts.indices.map { i ->
            PendingIntent.getBroadcast(
                app, requestCode++,
                Intent(ACTION_SENT).setPackage(app.packageName)
                    .putExtra(EXTRA_ID, id).putExtra(EXTRA_PART, i + 1),
                PendingIntent.FLAG_IMMUTABLE,
            )
        })
        try {
            sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            EventRecorder.line(
                app,
                "SMS: queued to ${mask(number)} (${parts.size} part(s), ${text.length} chars, try $attempt)",
            )
        } catch (e: Exception) {
            EventRecorder.line(app, "SMS: FAILED to ${mask(number)}: ${e.javaClass.simpleName} ${e.message}")
            scheduleRetry(app, number, text, attempt)
        }
    }

    private fun scheduleRetry(app: Context, number: String, text: String, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) {
            EventRecorder.line(app, "SMS: giving up on ${mask(number)} after $attempt tries")
            return
        }
        main.postDelayed({ sendTo(app, number, text, attempt + 1) }, RETRY_DELAY_MS)
    }

    private fun ensureSentReceiver(app: Context) {
        if (receiverRegistered) return
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getIntExtra(EXTRA_ID, -1)
                val out = outgoing[id] ?: return
                val number = out.number
                val part = intent.getIntExtra(EXTRA_PART, 0)
                val attempt = out.attempt
                val ok = resultCode == Activity.RESULT_OK
                EventRecorder.line(
                    context,
                    "SMS: to ${mask(number)} part $part (try $attempt): " +
                        if (ok) "sent ✓" else "error $resultCode${errorName(resultCode)}",
                )
                // Retry the whole text once per failure (judged on part 1 only, so
                // a 2-part text isn't retried twice).
                if (!ok && part == 1) {
                    scheduleRetry(context.applicationContext ?: context, number, out.text, attempt)
                }
                if (part == 1) outgoing.remove(id)
            }
        }, IntentFilter(ACTION_SENT), Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    /**
     * Dual-SIM phones: use the SIM set as default for SMS. If there's no default
     * ("ask every time"), fall back to the first active SIM.
     */
    private fun smsManager(app: Context): SmsManager {
        val base = app.getSystemService(SmsManager::class.java)
        var subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = try {
                app.getSystemService(SubscriptionManager::class.java)
                    .activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
                    ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            } catch (e: SecurityException) {
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
        }
        return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(subId) else base
    }

    private fun battery(app: Context): Int? =
        app.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

    private fun errorName(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> " (generic failure)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> " (radio off / airplane mode)"
        SmsManager.RESULT_ERROR_NO_SERVICE -> " (no service)"
        else -> ""
    }

    /** Logs show only the last 4 digits of a number. */
    private fun mask(number: String) = "…" + number.takeLast(4)
}
