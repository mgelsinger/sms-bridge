package io.github.mgelsinger.smsbridge.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import io.github.mgelsinger.smsbridge.core.CallRows
import io.github.mgelsinger.smsbridge.core.Telephone
import io.github.mgelsinger.smsbridge.protocol.Call
import io.github.mgelsinger.smsbridge.protocol.CallAbilities
import java.util.Locale

/**
 * Calls through the system's own services. The app is not the phone's dialer:
 * it reads the call log, asks Telecom to place an ordinary call, and asks it to
 * answer or end the one in progress. Each of those rests on a permission the
 * user grants separately from the SMS ones; without them texts work as before.
 */
class AndroidTelephone(private val context: Context) : Telephone {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun abilities(): CallAbilities {
        // Answering and ending act on what the line is doing, which the app only learns with READ_PHONE_STATE.
        val seesLine = granted(Manifest.permission.READ_PHONE_STATE)
        val steers = granted(Manifest.permission.ANSWER_PHONE_CALLS)
        return CallAbilities(
            history = granted(Manifest.permission.READ_CALL_LOG),
            dial = granted(Manifest.permission.CALL_PHONE) && telecom != null,
            answer = seesLine && steers && telecom != null,
            end = seesLine && steers && telecom != null,
        )
    }

    private val countryIso: String
        get() = listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)

    /** E.164 when the number parses, as message addresses are; otherwise as the phone has it. */
    fun normalize(raw: String): String = PhoneNumberUtils.formatNumberToE164(raw, countryIso) ?: raw

    override fun callsSince(afterId: Long, limit: Int): List<Call>? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()
        return try {
            context.contentResolver.query(uri, COLUMNS, "${CallLog.Calls._ID} > ?", arrayOf(afterId.toString()), "${CallLog.Calls._ID} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val shown = c.isNull(5) || c.getInt(5) == CallLog.Calls.PRESENTATION_ALLOWED
                            val number = c.getString(1)?.takeIf { shown && it.isNotBlank() }
                            add(
                                Call(
                                    id = "call:${c.getLong(0)}",
                                    number = number?.let(::normalize)?.take(64),
                                    kind = CallRows.kind(c.getInt(2)),
                                    date = c.getLong(3).coerceAtLeast(0),
                                    durationSeconds = c.getLong(4).coerceAtLeast(0),
                                ),
                            )
                        }
                    }
                }
        } catch (e: SecurityException) {
            null
        }
    }

    override fun latestCallId(): Long? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1").build()
        return try {
            context.contentResolver.query(uri, arrayOf(CallLog.Calls._ID), null, null, "${CallLog.Calls._ID} DESC")
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        } catch (e: SecurityException) {
            null
        }
    }

    /** When the phone cannot say, a short list of well known numbers is refused anyway. */
    override fun isEmergency(number: String): Boolean =
        runCatching { telephony?.isEmergencyNumber(number) }.getOrNull() ?: (number in WELL_KNOWN_EMERGENCY)

    override fun dial(number: String): String? = attempt("place calls") {
        checkNotNull(telecom).placeCall(Uri.fromParts("tel", number, null), Bundle())
        null
    }

    // Deprecated since Android 10 in favour of being the dialer, which this app is not.
    // Both still work for an app holding ANSWER_PHONE_CALLS.
    @Suppress("DEPRECATION")
    override fun answer(): String? = attempt("answer calls") {
        checkNotNull(telecom).acceptRingingCall()
        null
    }

    @Suppress("DEPRECATION")
    override fun end(): String? = attempt("end calls") {
        if (checkNotNull(telecom).endCall()) null else "the phone would not end the call"
    }

    private fun attempt(what: String, action: () -> String?): String? = try {
        action()
    } catch (e: SecurityException) {
        "the phone app may not $what"
    } catch (e: Exception) {
        "the phone could not do that (${e.javaClass.simpleName})"
    }

    private companion object {
        val COLUMNS = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER_PRESENTATION,
        )
        val WELL_KNOWN_EMERGENCY = setOf("911", "112", "999", "000", "110", "118", "119", "08")
    }
}
