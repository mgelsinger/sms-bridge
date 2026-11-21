package io.github.mgelsinger.smsbridge.platform

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.mgelsinger.smsbridge.core.EventHub
import io.github.mgelsinger.smsbridge.core.MmsPdu
import io.github.mgelsinger.smsbridge.core.PairedClient
import io.github.mgelsinger.smsbridge.core.SendHandler
import io.github.mgelsinger.smsbridge.core.SendTracker
import io.github.mgelsinger.smsbridge.core.SmsRepository
import io.github.mgelsinger.smsbridge.protocol.MmsAbilities
import io.github.mgelsinger.smsbridge.protocol.SendFrame
import io.github.mgelsinger.smsbridge.protocol.SendResultFrame
import io.github.mgelsinger.smsbridge.protocol.SendStatus
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import java.io.File
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
                ACTION_MMS_SENT -> {
                    File(File(app.cacheDir, MMS_DIR), "$clientId.pdu").delete()
                    val ok = resultCode == Activity.RESULT_OK
                    tracker.partSent(clientId, ok, if (ok) null else mmsErrorName(resultCode))
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
        val filter = IntentFilter(ACTION_SENT).apply {
            addAction(ACTION_DELIVERED)
            addAction(ACTION_MMS_SENT)
        }
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
        val recipients = frame.to ?: repo.threadParticipants(frame.threadId ?: -1)
        when {
            recipients == null -> return listOf(failure(frame, "no such thread"))
            recipients.isEmpty() -> return listOf(failure(frame, "NO_RECIPIENTS"))
        }
        if (frame.isMms || recipients.size > 1) return sendMms(client, frame, recipients)
        val address = recipients.single()
        val body = frame.body ?: return listOf(failure(frame, "nothing to send"))
        val sms = smsManager()
        val parts = sms.divideMessage(body)
        val sent = ArrayList(parts.indices.map { intent(ACTION_SENT, frame.clientId, it) })
        val delivered = ArrayList(parts.indices.map { intent(ACTION_DELIVERED, frame.clientId, it) })
        // Publish "queued" on the same stream as the later results, before the radio
        // can report "sent", so every PC sees them in order.
        hub.publish(tracker.begin(frame.clientId, address, body, parts.size))
        try {
            if (parts.size == 1) {
                sms.sendTextMessage(address, null, body, sent[0], delivered[0])
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

    /**
     * One MMS for the body and every attachment, to everyone in [recipients]. The PDU is
     * written to a private file the system's MMS service reads through a content URI; the
     * service sends it over mobile data and, because this is not the default SMS app,
     * stores the sent copy in the provider itself, where the watcher finds it.
     */
    private fun sendMms(client: PairedClient, frame: SendFrame, recipients: List<String>): List<WsFrame> {
        val limits = mmsAbilities()
        if (!limits.send) return listOf(failure(frame, "MMS_NOT_AVAILABLE"))
        val pdu = try {
            val attachments = frame.attachments.orEmpty().map {
                MmsPdu.Part(it.contentType, Base64.decode(it.data, Base64.DEFAULT), it.name)
            }
            MmsPdu.sendRequest("T" + frame.clientId.replace("-", ""), recipients, frame.body, attachments)
        } catch (e: IllegalArgumentException) {
            return listOf(failure(frame, "MMS_BAD_CONTENT"))
        }
        if (pdu.size > limits.maxBytes) return listOf(failure(frame, "MMS_TOO_LARGE"))
        val dir = File(app.cacheDir, MMS_DIR).apply { mkdirs() }
        val file = File(dir, "${frame.clientId}.pdu")
        file.writeBytes(pdu)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.mms", file)
        // The MMS service lives in the phone process on most builds, in its own package on others.
        for (pkg in MMS_SERVICE_PACKAGES) app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        hub.publish(tracker.beginMms(frame.clientId, recipients, frame.body))
        try {
            smsManager().sendMultimediaMessage(app, uri, null, null, intent(ACTION_MMS_SENT, frame.clientId, 0))
            log("sending MMS of ${pdu.size} bytes to ${recipients.size} recipient(s) for ${client.name}")
        } catch (e: Exception) {
            log("MMS refused by the system: ${e.javaClass.simpleName}")
            file.delete()
            tracker.partSent(frame.clientId, false, e.javaClass.simpleName)?.let(hub::publish)
        }
        return emptyList()
    }

    /** Whether this phone can send MMS now, and the carrier's size limit for one. */
    fun mmsAbilities(): MmsAbilities {
        val canSend = ContextCompat.checkSelfPermission(app, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val max = runCatching { smsManager().carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE) }.getOrNull()
            ?.takeIf { it > 0 } ?: DEFAULT_MMS_MAX_BYTES
        return MmsAbilities(send = canSend, maxBytes = max)
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

    private fun mmsErrorName(code: Int): String = when (code) {
        SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
        SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
        SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
        SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
        else -> "MMS_ERROR_$code"
    }

    companion object {
        private const val ACTION_MMS_SENT = "io.github.mgelsinger.smsbridge.MMS_SENT"
        private const val MMS_DIR = "mms-out"
        private val MMS_SERVICE_PACKAGES = listOf("com.android.phone", "com.android.mms.service")
        /** The platform's own default when the carrier configuration says nothing. */
        const val DEFAULT_MMS_MAX_BYTES = 300 * 1024
        private const val ACTION_SENT = "io.github.mgelsinger.smsbridge.SMS_SENT"
        private const val ACTION_DELIVERED = "io.github.mgelsinger.smsbridge.SMS_DELIVERED"
        private const val EXTRA_CLIENT_ID = "client_id"
        private const val EXTRA_PART = "part"
    }
}
