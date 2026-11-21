package io.github.mgelsinger.smsbridge.core

import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import io.github.mgelsinger.smsbridge.protocol.SendFrame
import io.github.mgelsinger.smsbridge.protocol.WsFrame
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The phone's own check of a `send` must agree with protocol/schema/ws-send.schema.json. */
class SendValidationTest {
    private val dir = File(System.getProperty("protocol.fixtures") ?: error("protocol.fixtures is not set"), "ws-send")

    private fun decode(f: File): SendFrame? =
        runCatching { ProtocolJson.decodeFromString(WsFrame.serializer(), f.readText()) as SendFrame }.getOrNull()

    @TestFactory
    fun validFixturesPass(): List<DynamicTest> = dir.listFiles { f -> f.extension == "json" }!!.sorted().map { f ->
        DynamicTest.dynamicTest(f.name) {
            val frame = decode(f)
            assertNotNull(frame, "decodes")
            assertNull(SendValidation.problem(frame!!))
        }
    }

    @TestFactory
    fun invalidFixturesFail(): List<DynamicTest> = dir.resolve("invalid").listFiles { f -> f.extension == "json" }!!.sorted().map { f ->
        DynamicTest.dynamicTest(f.name) {
            val frame = decode(f)
            assertTrue(frame == null || SendValidation.problem(frame) != null, "accepted ${f.name}")
        }
    }

    @Test
    fun aPictureOrSeveralPeopleMakeItAnMms() {
        val id = "8c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
        assertFalse(SendFrame(clientId = id, to = listOf("+15855550101"), body = "hi").isMms)
        assertFalse(SendFrame(clientId = id, threadId = 4, body = "hi").isMms)
        assertTrue(SendFrame(clientId = id, to = listOf("+15855550101", "+15855550177"), body = "hi").isMms)
        val pic = io.github.mgelsinger.smsbridge.protocol.OutgoingAttachment("image/jpeg", null, "AAAA")
        assertTrue(SendFrame(clientId = id, threadId = 4, attachments = listOf(pic)).isMms)
        assertEquals(
            "to must hold 1-20 different addresses",
            SendValidation.problem(SendFrame(clientId = id, to = listOf("+15855550101", "+15855550101"), body = "hi")),
        )
    }
}
