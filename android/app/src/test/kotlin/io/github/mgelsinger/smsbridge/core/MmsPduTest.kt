package io.github.mgelsinger.smsbridge.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

class MmsPduTest {
    private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun uintvar(n: Int) = ByteArrayOutputStream().also { MmsPdu.uintvar(it, n) }.toByteArray()

    @Test
    fun `uintvar is big-endian groups of seven bits`() {
        assertArrayEquals(hex(0x00), uintvar(0))
        assertArrayEquals(hex(0x7f), uintvar(127))
        assertArrayEquals(hex(0x81, 0x00), uintvar(128))
        assertArrayEquals(hex(0x83, 0xff, 0x7f), uintvar(65535))
    }

    @Test
    fun `the headers open with type, transaction id and version, and ask for the sender's number`() {
        val pdu = MmsPdu.sendRequest("T1", listOf("+15855550101"), "hi", emptyList())
        val expectedStart = hex(
            0x8C, 0x80, // m-send-req
            0x98, 'T'.code, '1'.code, 0, // transaction id
            0x8D, 0x92, // MMS 1.2
            0x89, 0x01, 0x81, // From: insert address
            0x97, // To
        ) + "+15855550101/TYPE=PLMN".toByteArray() + hex(0)
        assertArrayEquals(expectedStart, pdu.copyOf(expectedStart.size))
    }

    @Test
    fun `a group MMS has one To per recipient`() {
        val pdu = MmsPdu.sendRequest("T1", listOf("5855550101", "+1 (585) 555-0177"), "dinner?", emptyList())
        val headers = parse(pdu).headerTos
        assertEquals(listOf("5855550101/TYPE=PLMN", "+15855550177/TYPE=PLMN"), headers)
    }

    @Test
    fun `the body is SMIL first, then the attachments, then the text, with every length right`() {
        val jpeg = ByteArray(300) { it.toByte() }
        val pdu = MmsPdu.sendRequest("T1", listOf("+15855550101"), "Look at this", listOf(MmsPdu.Part("image/jpeg", jpeg, "IMG_2041.jpg")))
        val parsed = parse(pdu)
        assertEquals(listOf("smil", "image0", "text0"), parsed.parts.map { it.cid })
        assertEquals(listOf("smil.xml", "image0.jpg", "text0.txt"), parsed.parts.map { it.location })
        assertArrayEquals(jpeg, parsed.parts[1].data)
        assertEquals("Look at this", String(parsed.parts[2].data, Charsets.UTF_8))
        val smil = String(parsed.parts[0].data, Charsets.UTF_8)
        assertTrue(smil.contains("<img src=\"image0.jpg\" region=\"Image\"/><text src=\"text0.txt\" region=\"Text\"/>"), smil)
        // image/jpeg is well-known media 0x1E, with the name parameter after it.
        assertEquals(0x9E, parsed.parts[1].contentType[0].toInt() and 0xff)
        assertTrue(String(parsed.parts[1].contentType, Charsets.US_ASCII).contains("IMG_2041.jpg"))
    }

    @Test
    fun `text that is not ASCII is sent as UTF-8`() {
        val parsed = parse(MmsPdu.sendRequest("T1", listOf("+15855550101"), "Café 🎉", emptyList()))
        assertEquals("Café 🎉", String(parsed.parts.last().data, Charsets.UTF_8))
        // text/plain (0x83), charset (0x81) utf-8 (0xEA).
        assertArrayEquals(hex(0x83, 0x81, 0xEA), parsed.parts.last().contentType)
    }

    @Test
    fun `a picture alone has no text part`() {
        val parsed = parse(MmsPdu.sendRequest("T1", listOf("+15855550101"), null, listOf(MmsPdu.Part("image/png", ByteArray(10)))))
        assertEquals(listOf("smil", "image0"), parsed.parts.map { it.cid })
    }

    @Test
    fun `nothing to send or nobody to send it to is refused`() {
        assertThrows<IllegalArgumentException> { MmsPdu.sendRequest("T1", emptyList(), "hi", emptyList()) }
        assertThrows<IllegalArgumentException> { MmsPdu.sendRequest("T1", listOf("+15855550101"), "", emptyList()) }
        assertThrows<IllegalArgumentException> { MmsPdu.address("not a number") }
    }

    // A small reader for what MmsPdu writes, enough to check structure and lengths.
    private class ParsedPart(val contentType: ByteArray, val cid: String, val location: String, val data: ByteArray)
    private class Parsed(val headerTos: List<String>, val parts: List<ParsedPart>)

    private fun parse(pdu: ByteArray): Parsed {
        var i = 0
        fun byte() = pdu[i++].toInt() and 0xff
        fun text(): String { val start = i; while (pdu[i].toInt() != 0) i++; return String(pdu, start, i++ - start, Charsets.UTF_8) }
        fun uintvar(): Int { var v = 0; while (true) { val b = byte(); v = (v shl 7) or (b and 0x7f); if (b and 0x80 == 0) return v } }
        fun valueLength(): Int { val b = byte(); return if (b < 31) b else uintvar() }
        val tos = ArrayList<String>()
        loop@ while (true) {
            when (byte()) {
                MmsPdu.MESSAGE_TYPE, MmsPdu.MMS_VERSION, MmsPdu.MESSAGE_CLASS, MmsPdu.DELIVERY_REPORT, MmsPdu.READ_REPORT -> byte()
                MmsPdu.TRANSACTION_ID -> text()
                MmsPdu.FROM -> { val n = valueLength(); i += n }
                MmsPdu.TO -> tos += text()
                MmsPdu.CONTENT_TYPE -> { val n = valueLength(); i += n; break@loop }
                else -> error("unexpected header at ${i - 1}")
            }
        }
        val parts = ArrayList<ParsedPart>()
        repeat(uintvar()) {
            val headersLen = uintvar()
            val dataLen = uintvar()
            val headersEnd = i + headersLen
            val ctLen = valueLength()
            val ct = pdu.copyOfRange(i, i + ctLen); i += ctLen
            var cid = ""; var location = ""
            while (i < headersEnd) {
                when (byte()) {
                    0xC0 -> { check(byte() == 0x22); cid = text().removeSurrounding("<", ">") }
                    0x8E -> location = text()
                    else -> error("unexpected part header")
                }
            }
            check(i == headersEnd) { "part headers overran" }
            parts += ParsedPart(ct, cid, location, pdu.copyOfRange(i, i + dataLen)); i += dataLen
        }
        check(i == pdu.size) { "trailing bytes" }
        return Parsed(tos, parts)
    }
}
