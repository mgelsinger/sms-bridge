package io.github.mgelsinger.smsbridge.protocol

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every valid fixture in protocol/fixtures must decode into the Kotlin model and
 * encode back to exactly the same JSON. This keeps the phone's wire format and
 * the schemas in lockstep.
 */
class FixtureRoundTripTest {
    private val fixtureRoot: File = File(
        System.getProperty("protocol.fixtures") ?: error("protocol.fixtures system property is not set"),
    ).also { check(it.resolve("threads-page/default.json").isFile) { "no fixtures at $it" } }

    private val serializers: Map<String, KSerializer<*>> = mapOf(
        "message" to Message.serializer(),
        "thread" to ThreadSummary.serializer(),
        "contact" to Contact.serializer(),
        "contacts" to ContactsResponse.serializer(),
        "status" to StatusResponse.serializer(),
        "threads-page" to ThreadsPage.serializer(),
        "messages-page" to MessagesPage.serializer(),
        "sync-page" to SyncPage.serializer(),
        "pair-request" to PairRequest.serializer(),
        "pair-response" to PairResponse.serializer(),
        "pairing-qr" to PairingQr.serializer(),
        "error" to ErrorBody.serializer(),
    )

    private fun serializerFor(dir: String): KSerializer<*>? =
        if (dir.startsWith("ws-")) WsFrame.serializer() else serializers[dir]

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(serializer: KSerializer<*>, text: String): Pair<JsonElement, JsonElement> {
        val s = serializer as KSerializer<Any>
        val decoded = ProtocolJson.decodeFromString(s, text)
        val reencoded = ProtocolJson.encodeToJsonElement(s, decoded)
        return ProtocolJson.parseToJsonElement(text) to reencoded
    }

    @TestFactory
    fun everyValidFixtureRoundTrips(): List<DynamicTest> =
        fixtureRoot.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.flatMap { dir ->
            val serializer = serializerFor(dir.name)
            dir.listFiles()!!.filter { it.isFile && it.extension == "json" }.sortedBy { it.name }.map { file ->
                DynamicTest.dynamicTest("${dir.name}/${file.name}") {
                    checkNotNull(serializer) { "no Kotlin model mapped for fixtures/${dir.name}" }
                    val (expected, actual) = roundTrip(serializer, file.readText())
                    assertEquals(expected, actual)
                }
            }
        }

    @Test
    fun everyFixtureDirectoryHasAModel() {
        val unmapped = fixtureRoot.listFiles()!!.filter { it.isDirectory && serializerFor(it.name) == null }
        assertTrue(unmapped.isEmpty(), "unmapped fixture directories: ${unmapped.map { it.name }}")
    }

    @Test
    fun sendFrameOmitsTheUnusedTarget() {
        val json = ProtocolJson.encodeToString(
            WsFrame.serializer(),
            SendFrame(clientId = "3f0e6c1a-9b2d-4c7e-8a51-2d6f0b9e4c13", threadId = 11, body = "hi"),
        )
        assertTrue("\"to\"" !in json, json)
        assertTrue("\"type\":\"send\"" in json, json)
    }
}
