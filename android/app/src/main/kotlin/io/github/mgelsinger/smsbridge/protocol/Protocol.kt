@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mgelsinger.smsbridge.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire models for protocol v1. protocol/PROTOCOL.md and protocol/schema are the source of truth. */
const val PROTOCOL_VERSION = 1

val ProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
enum class MessageKind {
    @SerialName("sms") SMS,
    @SerialName("mms") MMS,
}

@Serializable
enum class Direction {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

@Serializable
enum class MessageStatus {
    @SerialName("pending") PENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class SendStatus {
    @SerialName("queued") QUEUED,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
}

@Serializable
enum class SimState {
    @SerialName("ready") READY,
    @SerialName("absent") ABSENT,
    @SerialName("locked") LOCKED,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class ErrorCode {
    @SerialName("bad_request") BAD_REQUEST,
    @SerialName("bad_cursor") BAD_CURSOR,
    @SerialName("unauthorized") UNAUTHORIZED,
    @SerialName("pairing_invalid") PAIRING_INVALID,
    @SerialName("pairing_rejected") PAIRING_REJECTED,
    @SerialName("rate_limited") RATE_LIMITED,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("unsupported_version") UNSUPPORTED_VERSION,
    @SerialName("not_default_sms_app") NOT_DEFAULT_SMS_APP,
    @SerialName("send_failed") SEND_FAILED,
    @SerialName("internal") INTERNAL,
}

@Serializable
data class MmsPart(
    @SerialName("part_id") val partId: Long,
    @SerialName("content_type") val contentType: String,
    val size: Long?,
    val name: String?,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("thread_id") val threadId: Long,
    val kind: MessageKind,
    val direction: Direction,
    val address: String,
    val participants: List<String>,
    val body: String?,
    val parts: List<MmsPart>,
    val date: Long,
    val read: Boolean,
    val status: MessageStatus?,
    @SerialName("client_id") val clientId: String?,
)

@Serializable
data class ThreadSummary(
    val id: Long,
    val participants: List<String>,
    val snippet: String?,
    val date: Long,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("message_count") val messageCount: Int,
)

@Serializable
data class Contact(
    val id: Long,
    val name: String,
    val numbers: List<String>,
)

@Serializable
data class DeviceStatus(
    @SerialName("device_name") val deviceName: String,
    @SerialName("battery_percent") val batteryPercent: Int?,
    val charging: Boolean?,
    @SerialName("signal_level") val signalLevel: Int?,
    @SerialName("sim_state") val simState: SimState,
    @SerialName("is_default_sms_app") val isDefaultSmsApp: Boolean,
    @SerialName("uptime_s") val uptimeSeconds: Long,
    @SerialName("paired_clients") val pairedClients: Int,
)

@Serializable
data class StatusResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("device_name") val deviceName: String,
    @SerialName("battery_percent") val batteryPercent: Int?,
    val charging: Boolean?,
    @SerialName("signal_level") val signalLevel: Int?,
    @SerialName("sim_state") val simState: SimState,
    @SerialName("is_default_sms_app") val isDefaultSmsApp: Boolean,
    @SerialName("uptime_s") val uptimeSeconds: Long,
    @SerialName("paired_clients") val pairedClients: Int,
) {
    companion object {
        fun of(s: DeviceStatus) = StatusResponse(
            deviceName = s.deviceName,
            batteryPercent = s.batteryPercent,
            charging = s.charging,
            signalLevel = s.signalLevel,
            simState = s.simState,
            isDefaultSmsApp = s.isDefaultSmsApp,
            uptimeSeconds = s.uptimeSeconds,
            pairedClients = s.pairedClients,
        )
    }
}

@Serializable
data class ThreadsPage(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val threads: List<ThreadSummary>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class MessagesPage(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("thread_id") val threadId: Long,
    val messages: List<Message>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class SyncPage(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val messages: List<Message>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class ContactsResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val contacts: List<Contact>,
)

@Serializable
data class PairRequest(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("client_name") val clientName: String,
)

@Serializable
data class PairResponse(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val token: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class PairingQr(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val host: String,
    val port: Int,
    val fingerprint: String,
    @SerialName("pairing_code") val pairingCode: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class ErrorDetail(val code: ErrorCode, val message: String)

@Serializable
data class ErrorBody(
    @SerialName("protocol_version") val protocolVersion: Int = PROTOCOL_VERSION,
    val error: ErrorDetail,
)

/** WebSocket frames in both directions. The "type" field is the class discriminator. */
@Serializable
sealed interface WsFrame {
    val protocolVersion: Int
}

@Serializable
@SerialName("hello")
data class HelloFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("device_name") val deviceName: String,
    @SerialName("sync_cursor") val syncCursor: String,
    @SerialName("is_default_sms_app") val isDefaultSmsApp: Boolean,
) : WsFrame

@Serializable
@SerialName("message")
data class MessageFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    val message: Message,
) : WsFrame

@Serializable
@SerialName("send_result")
data class SendResultFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("client_id") val clientId: String,
    @SerialName("message_id") val messageId: String?,
    val status: SendStatus,
    val error: String? = null,
) : WsFrame

@Serializable
@SerialName("thread_updated")
data class ThreadUpdatedFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("thread_id") val threadId: Long,
) : WsFrame

@Serializable
@SerialName("status")
data class StatusFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    val status: DeviceStatus,
) : WsFrame

@Serializable
@SerialName("pong")
data class PongFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
) : WsFrame

@Serializable
@SerialName("error")
data class ErrorFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    val code: ErrorCode,
    val message: String,
    @SerialName("client_id") val clientId: String? = null,
) : WsFrame

@Serializable
@SerialName("send")
data class SendFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("client_id") val clientId: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("thread_id") val threadId: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val to: List<String>? = null,
    val body: String,
) : WsFrame

@Serializable
@SerialName("mark_read")
data class MarkReadFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
    @SerialName("thread_id") val threadId: Long,
) : WsFrame

@Serializable
@SerialName("ping")
data class PingFrame(
    @SerialName("protocol_version") override val protocolVersion: Int = PROTOCOL_VERSION,
) : WsFrame
