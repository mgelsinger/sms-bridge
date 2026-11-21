package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.CallActionFrame
import io.github.mgelsinger.smsbridge.protocol.CallsPage
import io.github.mgelsinger.smsbridge.protocol.ContactsResponse
import io.github.mgelsinger.smsbridge.protocol.DeviceStatus
import io.github.mgelsinger.smsbridge.protocol.DialFrame
import io.github.mgelsinger.smsbridge.protocol.ErrorBody
import io.github.mgelsinger.smsbridge.protocol.ErrorCode
import io.github.mgelsinger.smsbridge.protocol.ErrorDetail
import io.github.mgelsinger.smsbridge.protocol.ErrorFrame
import io.github.mgelsinger.smsbridge.protocol.HelloFrame
import io.github.mgelsinger.smsbridge.protocol.MarkReadFrame
import io.github.mgelsinger.smsbridge.protocol.MessagesPage
import io.github.mgelsinger.smsbridge.protocol.PROTOCOL_VERSION
import io.github.mgelsinger.smsbridge.protocol.PairRequest
import io.github.mgelsinger.smsbridge.protocol.PairResponse
import io.github.mgelsinger.smsbridge.protocol.PingFrame
import io.github.mgelsinger.smsbridge.protocol.PongFrame
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.MmsAbilities
import io.github.mgelsinger.smsbridge.protocol.SendFrame
import io.github.mgelsinger.smsbridge.protocol.StatusResponse
import io.github.mgelsinger.smsbridge.protocol.SyncPage
import io.github.mgelsinger.smsbridge.protocol.ThreadsPage
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ApiResponse(val status: Int, val contentType: String, val body: ByteArray)

/** What to do after a client frame: frames to send back to that session, and an optional close. */
data class FrameReply(val frames: List<WsFrame> = emptyList(), val close: Pair<Int, String>? = null)

/** Handles sends. Phase 3 replaces the default, which refuses them. */
fun interface SendHandler {
    suspend fun send(client: PairedClient, frame: SendFrame): List<WsFrame>
}

/**
 * Transport-independent request handling for protocol v1. The Ktor server adapts
 * HTTP and WebSocket traffic to these calls; tests call them directly.
 */
class BridgeApi(
    private val repo: SmsRepository,
    private val pairing: PairingManager,
    private val confirmPairing: suspend (clientName: String) -> Boolean,
    private val startedAt: Long = System.currentTimeMillis(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sendHandler: SendHandler = SendHandler { _, frame ->
        listOf(ErrorFrame(code = ErrorCode.SEND_FAILED, message = "Sending is not enabled yet", clientId = frame.clientId))
    },
    private val log: (String) -> Unit = {},
    /** Null on a build or in a test without calls: the phone then says nothing about them. */
    private val calls: CallDesk? = null,
    /** Null on a build or in a test that cannot send MMS: the phone then says nothing about it. */
    private val mms: () -> MmsAbilities? = { null },
) {
    fun authenticate(authorization: String?): PairedClient? = pairing.authenticate(authorization)

    fun deviceStatus(): DeviceStatus {
        val info = repo.deviceInfo()
        return DeviceStatus(
            deviceName = info.deviceName,
            batteryPercent = info.batteryPercent,
            charging = info.charging,
            signalLevel = info.signalLevel,
            simState = info.simState,
            isDefaultSmsApp = info.isDefaultSmsApp,
            uptimeSeconds = (now() - startedAt) / 1000,
            pairedClients = pairing.clients.value.size,
            calls = calls?.abilities(),
            mms = mms(),
        )
    }

    /** Frames every session gets right after `hello`: what the line is doing now. */
    fun afterHello(): List<WsFrame> = listOfNotNull(calls?.lineFrame())

    fun hello(): HelloFrame {
        val info = repo.deviceInfo()
        return HelloFrame(
            deviceName = info.deviceName,
            syncCursor = repo.latestCursor().encode(),
            isDefaultSmsApp = info.isDefaultSmsApp,
        )
    }

    fun encode(frame: WsFrame): String = ProtocolJson.encodeToString(WsFrame.serializer(), frame)

    /**
     * Handles one HTTP request. [client] is null only for `/pair`, which the
     * server lets through without a token.
     */
    suspend fun handle(method: String, path: String, query: Map<String, String>, body: String?): ApiResponse {
        val started = System.nanoTime()
        val response = try {
            route(method, path, query, body)
        } catch (e: Exception) {
            log("ERROR $method $path: ${e.javaClass.simpleName}")
            error(500, ErrorCode.INTERNAL, "internal error")
        }
        // How long the phone took is what tells a slow provider from a slow network.
        log("$method $path ${response.status} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return response
    }

    private suspend fun route(method: String, path: String, query: Map<String, String>, body: String?): ApiResponse {
        if (method == "POST" && path == "/pair") return pair(body)
        if (method != "GET") return error(404, ErrorCode.NOT_FOUND, "no such endpoint")

        val threadMessages = THREAD_MESSAGES.matchEntire(path)
        return when {
            path == "/status" -> ok(StatusResponse.serializer(), StatusResponse.of(deviceStatus()))
            path == "/threads" -> {
                val limit = limit(query["limit"], 50, 200) ?: return error(400, ErrorCode.BAD_REQUEST, "limit must be 1-200")
                val before = query["cursor"]?.let { ThreadKey.decode(it) ?: return badCursor() }
                val page = repo.threadPage(limit, before)
                ok(ThreadsPage.serializer(), ThreadsPage(threads = page.items, nextCursor = page.next?.encode()))
            }
            threadMessages != null -> {
                val threadId = threadMessages.groupValues[1].toLongOrNull() ?: return error(404, ErrorCode.NOT_FOUND, "no such thread")
                val limit = limit(query["limit"], 100, 500) ?: return error(400, ErrorCode.BAD_REQUEST, "limit must be 1-500")
                val before = query["cursor"]?.let { MessageKey.decode(it) ?: return badCursor() }
                val page = repo.messagePage(threadId, limit, before) ?: return error(404, ErrorCode.NOT_FOUND, "no such thread")
                ok(
                    MessagesPage.serializer(),
                    MessagesPage(threadId = threadId, messages = page.items, nextCursor = page.next?.encode()),
                )
            }
            path == "/messages" -> {
                val limit = limit(query["limit"], 100, 500) ?: return error(400, ErrorCode.BAD_REQUEST, "limit must be 1-500")
                val raw = query["cursor"]
                    ?: return ok(SyncPage.serializer(), SyncPage(messages = emptyList(), nextCursor = repo.latestCursor().encode(), hasMore = false))
                val from = SyncCursor.decode(raw) ?: return badCursor()
                val result = repo.messagesSince(from, limit)
                ok(SyncPage.serializer(), SyncPage(messages = result.messages, nextCursor = result.next.encode(), hasMore = result.hasMore))
            }
            path == "/contacts" -> ok(ContactsResponse.serializer(), ContactsResponse(contacts = repo.contacts()))
            path == "/calls" && calls != null -> {
                val limit = limit(query["limit"], 200, 500) ?: return error(400, ErrorCode.BAD_REQUEST, "limit must be 1-500")
                val from = query["cursor"]?.let { CallCursor.decode(it) ?: return badCursor() }
                val page = calls.page(from, limit)
                    ?: return error(403, ErrorCode.NOT_PERMITTED, "the phone app may not read the call history")
                ok(CallsPage.serializer(), page)
            }
            else -> {
                CONTACT_PHOTO.matchEntire(path)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
                    val photo = repo.contactPhoto(id) ?: return error(404, ErrorCode.NOT_FOUND, "no photo")
                    return ApiResponse(200, photo.contentType, photo.bytes)
                }
                val part = MMS_PART.matchEntire(path)?.groupValues?.get(1)?.toLongOrNull()
                    ?: return error(404, ErrorCode.NOT_FOUND, "no such endpoint")
                val bytes = repo.mmsPart(part) ?: return error(404, ErrorCode.NOT_FOUND, "no such part")
                ApiResponse(200, bytes.contentType, bytes.bytes)
            }
        }
    }

    private suspend fun pair(body: String?): ApiResponse {
        val request = try {
            ProtocolJson.decodeFromString(PairRequest.serializer(), body.orEmpty())
        } catch (e: Exception) {
            return error(400, ErrorCode.BAD_REQUEST, "body must be a pair-request")
        }
        if (request.protocolVersion != PROTOCOL_VERSION) return error(400, ErrorCode.UNSUPPORTED_VERSION, "protocol version 1 only")
        if (!CODE.matches(request.pairingCode) || request.clientName.isBlank() || request.clientName.length > 64) {
            return error(400, ErrorCode.BAD_REQUEST, "pairing_code must be 6 digits and client_name 1-64 characters")
        }
        return when (pairing.checkCode(request.pairingCode)) {
            PairingManager.CodeCheck.LOCKED -> error(429, ErrorCode.RATE_LIMITED, "too many attempts, try again in 10 minutes")
            PairingManager.CodeCheck.INVALID -> error(403, ErrorCode.PAIRING_INVALID, "wrong or expired pairing code")
            PairingManager.CodeCheck.VALID -> {
                if (!confirmPairing(request.clientName)) {
                    error(403, ErrorCode.PAIRING_REJECTED, "pairing was declined on the phone")
                } else {
                    val (client, token) = pairing.issueToken(request.clientName)
                    log("paired client ${client.id}")
                    ok(PairResponse.serializer(), PairResponse(token = token, deviceName = repo.deviceInfo().deviceName))
                }
            }
        }
    }

    /** Handles one text frame from an authenticated session. */
    suspend fun onFrame(client: PairedClient, text: String): FrameReply {
        val obj = try {
            ProtocolJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return FrameReply(listOf(ErrorFrame(code = ErrorCode.BAD_REQUEST, message = "frame is not a JSON object")))

        val version = obj["protocol_version"]?.jsonPrimitive?.intOrNull
        if (version != PROTOCOL_VERSION) {
            return FrameReply(
                listOf(ErrorFrame(code = ErrorCode.UNSUPPORTED_VERSION, message = "this phone speaks protocol version 1")),
                close = 4000 to "unsupported protocol version",
            )
        }
        val type = obj["type"]?.jsonPrimitive?.content
        val known = type in CLIENT_TYPES || (calls != null && type in CALL_TYPES)
        if (!known) return FrameReply() // Unknown types are ignored for forward compatibility.

        val frame = try {
            ProtocolJson.decodeFromJsonElement(WsFrame.serializer(), obj)
        } catch (e: Exception) {
            return FrameReply(listOf(ErrorFrame(code = ErrorCode.BAD_REQUEST, message = "malformed $type frame", clientId = validClientId(obj))))
        }
        return when (frame) {
            is PingFrame -> FrameReply(listOf(PongFrame()))
            is MarkReadFrame -> FrameReply(
                listOf(ErrorFrame(code = ErrorCode.NOT_DEFAULT_SMS_APP, message = "Only the default SMS app can mark messages read")),
            )
            is SendFrame -> {
                val problem = SendValidation.problem(frame)
                if (problem != null) {
                    FrameReply(listOf(ErrorFrame(code = ErrorCode.BAD_REQUEST, message = problem, clientId = validClientId(obj))))
                } else {
                    FrameReply(sendHandler.send(client, frame))
                }
            }
            is DialFrame -> callReply(obj, calls?.problem(frame)) { calls?.dial(frame) }
            is CallActionFrame -> callReply(obj, calls?.problem(frame)) { calls?.act(frame) }
            else -> FrameReply()
        }
    }

    private fun callReply(obj: JsonObject, problem: String?, act: () -> WsFrame?): FrameReply =
        if (problem != null) {
            FrameReply(listOf(ErrorFrame(code = ErrorCode.BAD_REQUEST, message = problem, clientId = validClientId(obj))))
        } else {
            FrameReply(listOfNotNull(act()))
        }

    private fun validClientId(obj: JsonObject): String? =
        (obj["client_id"]?.jsonPrimitive?.content)?.takeIf { SendValidation.UUID_V4.matches(it) }

    private fun <T> ok(serializer: KSerializer<T>, value: T): ApiResponse =
        ApiResponse(200, JSON, ProtocolJson.encodeToString(serializer, value).toByteArray())

    private fun error(status: Int, code: ErrorCode, message: String): ApiResponse =
        ApiResponse(status, JSON, ProtocolJson.encodeToString(ErrorBody.serializer(), ErrorBody(error = ErrorDetail(code, message))).toByteArray())

    private fun badCursor() = error(400, ErrorCode.BAD_CURSOR, "unknown cursor")

    private fun limit(raw: String?, default: Int, max: Int): Int? {
        if (raw == null) return default
        val n = raw.toIntOrNull() ?: return null
        return n.takeIf { it in 1..max }
    }

    companion object {
        const val JSON = "application/json; charset=utf-8"
        private val THREAD_MESSAGES = Regex("^/threads/(\\d{1,18})/messages$")
        private val MMS_PART = Regex("^/mms/(\\d{1,18})$")
        private val CONTACT_PHOTO = Regex("^/contacts/(\\d{1,18})/photo$")
        private val CODE = Regex("^\\d{6}$")
        private val CLIENT_TYPES = setOf("send", "ping", "mark_read")
        private val CALL_TYPES = setOf("dial", "call_action")

        /** Error body for requests the server refuses before they reach [handle]. */
        fun unauthorized(): ApiResponse = ApiResponse(
            401,
            JSON,
            ProtocolJson.encodeToString(
                ErrorBody.serializer(),
                ErrorBody(error = ErrorDetail(ErrorCode.UNAUTHORIZED, "missing or unknown token")),
            ).toByteArray(),
        )
    }
}

object SendValidation {
    val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    const val MAX_BODY = 5000

    /** Mirrors protocol/schema/ws-send.schema.json. Null when the frame is valid. */
    fun problem(frame: SendFrame): String? = when {
        !UUID_V4.matches(frame.clientId) -> "client_id must be a lowercase UUID v4"
        (frame.threadId == null) == (frame.to == null) -> "exactly one of thread_id or to is required"
        frame.to != null && (frame.to.size !in 1..MAX_RECIPIENTS || frame.to.any { it.isBlank() || it.length > 64 } || frame.to.toSet().size != frame.to.size) ->
            "to must hold 1-$MAX_RECIPIENTS different addresses"
        frame.threadId != null && frame.threadId < 0 -> "thread_id must not be negative"
        frame.body == null && frame.attachments == null -> "a send needs a body, attachments, or both"
        frame.body != null && (frame.body.isEmpty() || frame.body.length > MAX_BODY) -> "body must be 1-$MAX_BODY characters"
        frame.attachments != null && frame.attachments.size !in 1..MAX_ATTACHMENTS -> "attachments must hold 1-$MAX_ATTACHMENTS items"
        frame.attachments?.any { !ATTACHMENT_TYPE.matches(it.contentType) } == true -> "attachments may be pictures, video, audio, or contact cards"
        frame.attachments?.any { it.data.length !in 4..MAX_ATTACHMENT_CHARS || !BASE64.matches(it.data) } == true -> "attachment data must be base64"
        frame.attachments?.any { it.name != null && it.name.length !in 1..120 } == true -> "attachment names must be 1-120 characters"
        else -> null
    }

    const val MAX_RECIPIENTS = 20
    const val MAX_ATTACHMENTS = 10
    const val MAX_ATTACHMENT_CHARS = 4_000_000
    private val ATTACHMENT_TYPE = Regex("^((image|video|audio)/[a-z0-9.+-]+|text/x-vcard)$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
}
