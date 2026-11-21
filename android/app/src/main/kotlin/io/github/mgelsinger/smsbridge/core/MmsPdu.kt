package io.github.mgelsinger.smsbridge.core

import java.io.ByteArrayOutputStream

/**
 * Encodes an MMS send request (m-send-req, OMA MMS Encapsulation 1.2) for
 * SmsManager.sendMultimediaMessage. Android offers no public builder, and the usual
 * library for it is an unmaintained copy of the platform's internal one; this writes
 * only the one message type the bridge sends. Pure, so it is unit tested.
 */
object MmsPdu {
    class Part(val contentType: String, val bytes: ByteArray, val name: String? = null)

    /**
     * [to] are phone numbers (more than one makes a group MMS); [text] becomes a
     * text/plain part. The From header asks the network to insert the phone's own number.
     */
    fun sendRequest(transactionId: String, to: List<String>, text: String?, attachments: List<Part>): ByteArray {
        require(to.isNotEmpty()) { "an MMS needs a recipient" }
        require(!text.isNullOrEmpty() || attachments.isNotEmpty()) { "an MMS needs content" }
        val parts = buildParts(text, attachments)
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE); out.write(M_SEND_REQ)
        out.write(TRANSACTION_ID); textString(out, transactionId)
        out.write(MMS_VERSION); out.write(0x80 or 0x12) // 1.2
        out.write(FROM); out.write(1); out.write(INSERT_ADDRESS_TOKEN)
        for (number in to) {
            out.write(TO); textString(out, address(number))
        }
        out.write(MESSAGE_CLASS); out.write(CLASS_PERSONAL)
        out.write(DELIVERY_REPORT); out.write(NO)
        out.write(READ_REPORT); out.write(NO)
        // Content-Type comes last: multipart/related, starting at the SMIL part.
        out.write(CONTENT_TYPE)
        val ct = ByteArrayOutputStream().apply {
            write(0x80 or WK_MULTIPART_RELATED)
            write(PARAM_TYPE); textString(this, "application/smil")
            write(PARAM_START); textString(this, "<smil>")
        }.toByteArray()
        valueLength(out, ct.size)
        out.write(ct)
        // Body.
        uintvar(out, parts.size)
        for (p in parts) {
            val headers = ByteArrayOutputStream().apply {
                contentType(this, p.contentType, p.name)
                write(CONTENT_ID); write(0x22); writeAscii(this, "<${p.cid}>"); write(0)
                write(CONTENT_LOCATION); textString(this, p.location)
            }.toByteArray()
            uintvar(out, headers.size)
            uintvar(out, p.bytes.size)
            out.write(headers)
            out.write(p.bytes)
        }
        return out.toByteArray()
    }

    private class Built(val contentType: String, val bytes: ByteArray, val cid: String, val location: String, val name: String?)

    private fun buildParts(text: String?, attachments: List<Part>): List<Built> {
        val media = attachments.mapIndexed { i, a ->
            val kind = when {
                a.contentType.startsWith("image/") -> "image"
                a.contentType.startsWith("video/") -> "video"
                a.contentType.startsWith("audio/") -> "audio"
                else -> "file"
            }
            val ext = extension(a.contentType)
            Built(a.contentType, a.bytes, "$kind$i", "$kind$i$ext", a.name?.let(::asciiName))
        }
        val textPart = text?.takeIf { it.isNotEmpty() }?.let {
            Built("text/plain", it.toByteArray(Charsets.UTF_8), "text0", "text0.txt", null)
        }
        val smil = smil(media, textPart).toByteArray(Charsets.UTF_8)
        return listOf(Built("application/smil", smil, "smil", "smil.xml", null)) + media + listOfNotNull(textPart)
    }

    /** One slide per attachment, the text on the last, which is how phones lay out a picture with a caption. */
    private fun smil(media: List<Built>, text: Built?): String {
        val slides = StringBuilder()
        media.forEachIndexed { i, m ->
            val tag = when {
                m.contentType.startsWith("image/") -> "img"
                m.contentType.startsWith("video/") -> "video"
                m.contentType.startsWith("audio/") -> "audio"
                else -> "ref"
            }
            slides.append("<par dur=\"5000ms\"><$tag src=\"${m.location}\" region=\"Image\"/>")
            if (text != null && i == media.lastIndex) slides.append("<text src=\"${text.location}\" region=\"Text\"/>")
            slides.append("</par>")
        }
        if (media.isEmpty() && text != null) slides.append("<par dur=\"5000ms\"><text src=\"${text.location}\" region=\"Text\"/></par>")
        return "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\" fit=\"meet\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>" +
            "</layout></head><body>$slides</body></smil>"
    }

    /** A number as an MMS address: digits with an optional leading +, then the PLMN type. */
    fun address(number: String): String {
        val t = number.trim()
        val digits = t.filter(Char::isDigit)
        require(digits.isNotEmpty()) { "not a phone number" }
        return (if (t.startsWith("+")) "+" else "") + digits + "/TYPE=PLMN"
    }

    private fun contentType(out: ByteArrayOutputStream, type: String, name: String?) {
        val params = ByteArrayOutputStream()
        val known = WELL_KNOWN_MEDIA[type.lowercase()]
        if (known != null) params.write(0x80 or known) else textString(params, type.lowercase())
        if (type.startsWith("text/")) {
            params.write(PARAM_CHARSET); params.write(0x80 or CHARSET_UTF8)
        }
        if (name != null) {
            params.write(PARAM_NAME); textString(params, name)
        }
        val bytes = params.toByteArray()
        valueLength(out, bytes.size)
        out.write(bytes)
    }

    private fun extension(type: String): String = when (type.lowercase()) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/heic" -> ".heic"
        "video/mp4" -> ".mp4"
        "video/3gpp" -> ".3gp"
        "audio/amr" -> ".amr"
        "audio/mp4", "audio/m4a" -> ".m4a"
        "audio/mpeg" -> ".mp3"
        "text/x-vcard" -> ".vcf"
        else -> ""
    }

    /** Names travel as plain text strings; keep them ASCII and short so no carrier trips on them. */
    private fun asciiName(name: String): String? =
        name.map { if (it.code in 0x20..0x7e && it != '"') it else '_' }.joinToString("").trim().take(60).ifEmpty { null }

    private fun writeAscii(out: ByteArrayOutputStream, s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

    /** Text-string: the text, NUL-terminated, with a quote byte first if it would read as a number. */
    internal fun textString(out: ByteArrayOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xff) >= 0x80) out.write(0x7f)
        out.write(bytes)
        out.write(0)
    }

    internal fun valueLength(out: ByteArrayOutputStream, n: Int) {
        if (n < 31) out.write(n) else { out.write(31); uintvar(out, n) }
    }

    internal fun uintvar(out: ByteArrayOutputStream, n: Int) {
        require(n >= 0)
        val groups = ArrayList<Int>()
        var v = n
        do { groups.add(v and 0x7f); v = v ushr 7 } while (v != 0)
        for (i in groups.indices.reversed()) out.write(if (i > 0) groups[i] or 0x80 else groups[i])
    }

    // Header field names (WAP short-integers, high bit set).
    const val MESSAGE_TYPE = 0x8C
    const val M_SEND_REQ = 0x80
    const val TRANSACTION_ID = 0x98
    const val MMS_VERSION = 0x8D
    const val FROM = 0x89
    const val TO = 0x97
    const val MESSAGE_CLASS = 0x8A
    const val DELIVERY_REPORT = 0x86
    const val READ_REPORT = 0x90
    const val CONTENT_TYPE = 0x84
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val CLASS_PERSONAL = 0x80
    private const val NO = 0x81
    // Part headers and parameters.
    private const val CONTENT_LOCATION = 0x8E
    private const val CONTENT_ID = 0xC0
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_NAME = 0x85
    private const val PARAM_TYPE = 0x89
    private const val PARAM_START = 0x8A
    private const val CHARSET_UTF8 = 0x6A
    private const val WK_MULTIPART_RELATED = 0x33
    private val WELL_KNOWN_MEDIA = mapOf(
        "text/plain" to 0x03,
        "text/x-vcard" to 0x07,
        "image/gif" to 0x1D,
        "image/jpeg" to 0x1E,
        "image/jpg" to 0x1E,
        "image/png" to 0x20,
    )
}
