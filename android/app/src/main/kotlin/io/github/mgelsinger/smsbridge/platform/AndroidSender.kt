package io.github.mgelsinger.smsbridge.platform

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.PairedClient
import io.github.mgelsinger.smsbridge.core.SendHandler
import io.github.mgelsinger.smsbridge.core.SendTracker
import io.github.mgelsinger.smsbridge.core.SmsRepository
import io.github.mgelsinger.smsbridge.protocol.SendFrame
import io.github.mgelsinger.smsbridge.protocol.SendResultFrame
import io.github.mgelsinger.smsbridge.protocol.SendStatus
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends SMS through the phone's default subscription. Progress comes back as
 * sent and delivery broadcasts per part; [SendTracker] turns those into
 * send_result frames for every connected PC. The system stores each sent
 * message in the provider, where the watcher picks it up and the tracker
 * attaches the client_id.
 */
class AndroidSender(
    context: Context,
    private val repo: SmsRepository,
    private val tracker: SendTracker,
    private val hub: EventHub,
    private val log: (String) -> Unit,
) : SendHandler {
    private val app = context.applicationContext
    private val requestCodes = AtomicInteger(1)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return
            val frame = when (intent.action) {
                ACTION_SENT -> {
                    val ok = resultCode == Activity.RESULT_OK
                    tracker.partSent(clientId, ok, if (ok) null else errorName(resultCode))
                }
                ACTION_DELIVERED -> when (deliveryOutcome(intent)) {
                    true -> tracker.partDelivered(clientId, true)
                    false -> tracker.partDelivered(clientId, false)
                    null -> null // The network is still trying.
                }
                else -> null
            }
            frame?.let {
                hub.publish(it)
                log("send ${it.status.name.lowercase()}")
            }
        }
    }

    @Synchronized
    fun register() {
        if (registered) return
        val filter = IntentFilter(ACTION_SENT).apply { addAction(ACTION_DELIVERED) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        runCatching { app.unregisterReceiver(receiver) }
        registered = false
    }

    override suspend fun send(client: PairedClient, frame: SendFrame): List<WsFrame> {
        tracker.known(frame.clientId)?.let { return listOf(it) }
        register()
        val address = frame.to?.single() ?: run {
            val people = repo.threadParticipants(frame.threadId ?: -1)
            when {
                people == null -> return listOf(failure(frame, "no such thread"))
                people.size != 1 -> return listOf(failure(frame, "group threads need MMS, which this version does not send"))
                else -> people.single()
            }
        }
        val sms = smsManager()
        val parts = sms.divideMessage(frame.body)
        val sent = ArrayList(parts.indices.map { intent(ACTION_SENT, frame.clientId, it) })
        val delivered = ArrayList(parts.indices.map { intent(ACTION_DELIVERED, frame.clientId, it) })
        // Publish "queued" on the same stream as the later results, before the radio
        // can report "sent", so every PC sees them in order.
        hub.publish(tracker.begin(frame.clientId, address, frame.body, parts.size))
        try {
            if (parts.size == 1) {
                sms.sendTextMessage(address, null, frame.body, sent[0], delivered[0])
            } else {
                sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
            }
            log("sending ${parts.size} part(s) for ${client.name}")
        } catch (e: Exception) {
            log("send refused by the system: ${e.javaClass.simpleName}")
            tracker.partSent(frame.clientId, false, e.javaClass.simpleName)?.let(hub::publish)
        }
        return emptyList()
    }

    private fun failure(frame: SendFrame, why: String): SendResultFrame {
        tracker.begin(frame.clientId, frame.to?.firstOrNull().orEmpty(), frame.body, 1)
        return tracker.partSent(frame.clientId, false, why)
            ?: SendResultFrame(clientId = frame.clientId, messageId = null, status = SendStatus.FAILED, error = why)
    }

    private fun intent(action: String, clientId: String, part: Int): PendingIntent = PendingIntent.getBroadcast(
        app,
        requestCodes.getAndIncrement(),
        Intent(action).setPackage(app.packageName).putExtra(EXTRA_CLIENT_ID, clientId).putExtra(EXTRA_PART, part),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
    )

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** True delivered, false failed, null still pending (TP-Status 0x20-0x3F). */
    private fun deliveryOutcome(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return true
        val format = intent.getStringExtra("format")
        val status = runCatching { SmsMessage.createFromPdu(pdu, format)?.status }.getOrNull() ?: return true
        return when {
            status < 0x20 -> true
            status < 0x40 -> null
            else -> false
        }
    }

    private fun errorName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "RESULT_ERROR_LIMIT_EXCEEDED"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "RESULT_ERROR_SHORT_CODE_NOT_ALLOWED"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED"
        else -> "RESULT_ERROR_$code"
    }

    companion object {
        private const val ACTION_SENT = "io.github.mgelsinger.smsbridge.SMS_SENT"
        private const val ACTION_DELIVERED = "io.github.mgelsinger.smsbridge.SMS_DELIVERED"
        private const val EXTRA_CLIENT_ID = "client_id"
        private const val EXTRA_PART = "part"
    }
}
