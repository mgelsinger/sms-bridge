package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.Direction
import io.github.mgelsinger.smsbridge.protocol.Message
import io.github.mgelsinger.smsbridge.protocol.MessageKind
import io.github.mgelsinger.smsbridge.protocol.MessageStatus
import io.github.mgelsinger.smsbridge.protocol.MmsPart

/** A row of content://sms. Column meanings follow android.provider.Telephony.Sms. */
data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val body: String?,
    val date: Long,
    val type: Int,
    val read: Boolean,
    val status: Int,
)

/** A row of content://mms joined with its addresses and parts. Dates are seconds, as Android stores them. */
data class MmsRow(
    val id: Long,
    val threadId: Long,
    val dateSeconds: Long,
    val messageBox: Int,
    val read: Boolean,
    val from: String?,
    val to: List<String>,
    val text: String?,
    val parts: List<MmsPart>,
)

object RowMapping {
    // Telephony.Sms.MESSAGE_TYPE_*
    const val SMS_INBOX = 1
    const val SMS_SENT = 2
    const val SMS_DRAFT = 3
    const val SMS_OUTBOX = 4
    const val SMS_FAILED = 5
    const val SMS_QUEUED = 6

    // Telephony.Sms.STATUS_*
    const val STATUS_COMPLETE = 0
    const val STATUS_FAILED = 64

    // Telephony.Mms.MESSAGE_BOX_*
    const val MMS_INBOX = 1
    const val MMS_SENT = 2
    const val MMS_DRAFTS = 3
    const val MMS_OUTBOX = 4
    const val MMS_FAILED = 5

    /** The placeholder Android stores as the sender of MMS this phone sent. */
    const val INSERT_ADDRESS_TOKEN = "insert-address-token"

    /** Returns null for drafts and rows without an address, which are not messages yet. */
    fun sms(row: SmsRow, participants: List<String>, normalize: (String) -> String): Message? {
        if (row.type == SMS_DRAFT) return null
        val raw = row.address?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val address = normalize(raw)
        val outgoing = row.type != SMS_INBOX
        val status = when {
            !outgoing -> null
            row.type == SMS_FAILED -> MessageStatus.FAILED
            row.type == SMS_OUTBOX || row.type == SMS_QUEUED -> MessageStatus.PENDING
            row.status == STATUS_COMPLETE -> MessageStatus.DELIVERED
            row.status == STATUS_FAILED -> MessageStatus.FAILED
            else -> MessageStatus.SENT
        }
        return Message(
            id = "sms:${row.id}",
            threadId = row.threadId,
            kind = MessageKind.SMS,
            direction = if (outgoing) Direction.OUT else Direction.IN,
            address = address,
            participants = participants.ifEmpty { listOf(address) }.distinct(),
            body = row.body,
            parts = emptyList(),
            date = row.date,
            read = row.read,
            status = status,
            clientId = null,
        )
    }

    fun mms(row: MmsRow, participants: List<String>, normalize: (String) -> String): Message? {
        if (row.messageBox == MMS_DRAFTS) return null
        val outgoing = row.messageBox != MMS_INBOX
        val candidates = if (outgoing) row.to else listOfNotNull(row.from)
        val raw = candidates.map { it.trim() }.firstOrNull { it.isNotEmpty() && it != INSERT_ADDRESS_TOKEN }
            ?: participants.firstOrNull()
            ?: return null
        val address = normalize(raw)
        val status = when {
            !outgoing -> null
            row.messageBox == MMS_FAILED -> MessageStatus.FAILED
            row.messageBox == MMS_OUTBOX -> MessageStatus.PENDING
            else -> MessageStatus.SENT
        }
        return Message(
            id = "mms:${row.id}",
            threadId = row.threadId,
            kind = MessageKind.MMS,
            direction = if (outgoing) Direction.OUT else Direction.IN,
            address = address,
            participants = participants.ifEmpty { listOf(address) }.distinct(),
            body = row.text?.takeIf { it.isNotBlank() },
            parts = row.parts,
            date = row.dateSeconds * 1000,
            read = row.read,
            status = status,
            clientId = null,
        )
    }

    /** Parts that are not message content: the text itself and SMIL layout. */
    fun isAttachment(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct != "text/plain" && ct != "application/smil"
    }
}
