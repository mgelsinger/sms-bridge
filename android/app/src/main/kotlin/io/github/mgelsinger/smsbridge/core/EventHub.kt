package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.WsFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Fans phone events out to every connected WebSocket session. */
class EventHub {
    private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()

    private val _revoked = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Client ids whose sessions must close with 4001. */
    val revoked: SharedFlow<String> = _revoked.asSharedFlow()

    fun publish(frame: WsFrame) {
        _frames.tryEmit(frame)
    }

    fun revoke(clientId: String) {
        _revoked.tryEmit(clientId)
    }
}
