package com.vayunmathur.email.mime

import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.network.imap.MimeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins header-block parsing against hand-written vectors, not against itself.
 *
 * A disagreement here does not throw: a folded subject silently gains a space,
 * a missed boundary param silently drops every attachment, and a lowercasing
 * miss silently files mail under a duplicate folder. Each test names the wire
 * shape that would expose the regression.
 */
class MimeHeaderParsingTest {

    @Test
    fun `unfolds continuation lines onto the header value`() {
        val headers = MimeParser.parseHeadersString(
            "Subject: a long\r\n continued\r\n\tand tabulated\r\nFrom: a@b",
        )
        assertEquals("a long continued and tabulated", headers["subject"])
    }

    @Test
    fun `header names are case insensitive and values trimmed`() {
        val headers = MimeParser.parseHeadersString("SUBJECT:  Hi  \r\nfRoM:a@b")
        assertEquals("Hi", headers["subject"])
        assertEquals("a@b", headers["from"])
    }

    @Test
    fun `lines without a colon are ignored`() {
        val headers = MimeParser.parseHeadersString(
            "Subject: ok\r\nThis line has no colon\r\nFrom: a@b",
        )
        assertEquals(2, headers.size)
        assertEquals("ok", headers["subject"])
    }

    @Test
    fun `later duplicate headers overwrite earlier ones`() {
        val headers = MimeParser.parseHeadersString("Subject: first\r\nSubject: second")
        assertEquals("second", headers["subject"])
    }

    @Test
    fun `header block bytes decode as utf8 and keep unicode`() {
        val raw = "Subject: Привет 🌍\r\nFrom: José <jose@example.com>\r\n\r\nbody"
        val headers = MimeParser.parseHeaderBlockBytes(raw.toByteArray(Charsets.UTF_8))
        assertEquals("Привет 🌍", headers["subject"])
        assertEquals("José <jose@example.com>", headers["from"])
    }

    @Test
    fun `missing content type defaults to text plain`() {
        assertEquals("text/plain", MimeParser.parseContentType(null).fullType)
        assertEquals("text/plain", MimeParser.parseContentType("  ").fullType)
        assertTrue(MimeParser.parseContentType(null).isText)
    }

    @Test
    fun `content type params keep quoted semicolons intact`() {
        val ct = MimeParser.parseContentType("multipart/mixed; boundary=\"a;b\"; charset=UTF-8")
        assertTrue(ct.isMultipart)
        assertEquals("a;b", ct.params["boundary"])
        assertEquals("UTF-8", ct.params["charset"])
    }

    @Test
    fun `content type names are lowercased`() {
        val ct = MimeParser.parseContentType("TEXT/HTML; CHARSET=UTF-8")
        assertEquals("text", ct.mainType)
        assertEquals("html", ct.subType)
    }

    @Test
    fun `disposition exposes filename from either param`() {
        val attachment = MimeParser.parseDisposition("attachment; filename=\"a b.pdf\"")
        assertTrue(attachment?.isAttachment == true)
        assertEquals("a b.pdf", attachment?.filename)
        val inline = MimeParser.parseDisposition("INLINE; name=\"i.png\"")
        assertTrue(inline?.isInline == true)
        assertEquals("i.png", inline?.filename)
    }

    @Test
    fun `blank disposition input yields null`() {
        assertNull(MimeParser.parseDisposition(null))
        assertNull(MimeParser.parseDisposition("   "))
    }

    @Test
    fun `content id strips brackets and rejects blanks`() {
        assertEquals("abc@x", MimeParser.extractCid("<abc@x>"))
        assertEquals("abc", MimeParser.extractCid("abc"))
        assertNull(MimeParser.extractCid("   "))
        assertNull(MimeParser.extractCid("<>"))
    }

    @Test
    fun `extractHeaderValue returns plain values and null when missing`() {
        val headers = mapOf("subject" to "Hello")
        assertEquals("Hello", MimeParser.extractHeaderValue(headers, "Subject"))
        assertNull(MimeParser.extractHeaderValue(headers, "From"))
    }

    @Test
    fun `sender display name prefers the angle bracket name`() {
        assertEquals("José García", senderDisplayName("José García <jose@example.com>"))
        assertEquals("Doe, John", senderDisplayName("\"Doe, John\" <john@example.com>"))
    }

    @Test
    fun `sender display name falls back to bare address or blank`() {
        assertEquals("bob@example.com", senderDisplayName("bob@example.com"))
        assertEquals("No Brackets Name", senderDisplayName("No Brackets Name"))
        assertEquals("", senderDisplayName("   "))
    }
}
