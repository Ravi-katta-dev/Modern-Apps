package com.vayunmathur.email.mime

import com.vayunmathur.email.network.imap.MimeParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins body decoding and part splitting against fixed vectors.
 *
 * Only transfer encodings that are pure JVM are exercised here: 7bit and
 * quoted-printable. Base64 goes through the platform decoder, so it cannot
 * run as a host test and is deliberately absent.
 */
class MimeBodyDecodingTest {

    private fun qpBytes(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun qpToString(text: String): String {
        return String(MimeParser.decodeQuotedPrintable(qpBytes(text)), Charsets.UTF_8)
    }

    @Test
    fun `quoted printable decodes hex escapes`() {
        assertEquals("Hello World!", qpToString("Hello=20World=21"))
        assertEquals("J", qpToString("=4A"))
    }

    @Test
    fun `quoted printable joins soft line breaks`() {
        assertEquals("Hello", qpToString("Hel=\r\nlo"))
        assertEquals("Hello", qpToString("Hel=\nlo"))
    }

    @Test
    fun `quoted printable keeps a lone trailing equals literal`() {
        assertContentEquals(ByteArray(0), MimeParser.decodeQuotedPrintable(qpBytes("=")))
        assertEquals("=ZZ", qpToString("=ZZ"))
    }

    @Test
    fun `quoted printable preserves multibyte utf8`() {
        assertEquals("é", qpToString("=C3=A9"))
    }

    @Test
    fun `splitMultipart extracts each part between delimiters`() {
        val body = "--b\r\nA\r\n--b\r\nB\r\n--b--\r\n"
        val parts = MimeParser.splitMultipart(body.toByteArray(Charsets.ISO_8859_1), "b")
        assertEquals(2, parts.size)
        assertEquals("A", String(parts[0], Charsets.ISO_8859_1))
        assertEquals("B", String(parts[1], Charsets.ISO_8859_1))
    }

    @Test
    fun `splitMultipart is empty without a boundary or delimiters`() {
        val body = "--b\r\nA\r\n--b--\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue(MimeParser.splitMultipart(body, "").isEmpty())
        val plain = "just text".toByteArray(Charsets.ISO_8859_1)
        assertTrue(MimeParser.splitMultipart(plain, "b").isEmpty())
    }

    @Test
    fun `splitMultipart keeps the tail when the close is missing`() {
        val body = "--b\r\nonly part, no close".toByteArray(Charsets.ISO_8859_1)
        val parts = MimeParser.splitMultipart(body, "b")
        assertEquals(1, parts.size)
        assertTrue(String(parts[0], Charsets.ISO_8859_1).contains("only part"))
    }

    @Test
    fun `decodeCharset resolves aliases and falls back for unknown names`() {
        val hello = "héllo".toByteArray(Charsets.UTF_8)
        assertEquals("héllo", MimeParser.decodeCharset(hello, "utf-8"))
        assertEquals("héllo", MimeParser.decodeCharset(hello, "utf8"))
        assertEquals("é", MimeParser.decodeCharset(byteArrayOf(0xE9.toByte()), "latin1"))
        assertEquals("hi", MimeParser.decodeCharset("hi".toByteArray(), "x-no-such-charset"))
    }

    @Test
    fun `sanitizeFilename strips directories and caps length`() {
        assertEquals("c.txt", MimeParser.sanitizeFilename("a/b\\c.txt"))
        assertEquals("a_b__c", MimeParser.sanitizeFilename("a\"b\r\nc"))
        assertEquals("attachment", MimeParser.sanitizeFilename("   "))
        val long = MimeParser.sanitizeFilename("x".repeat(200))
        assertTrue(long.length <= 120)
    }

    @Test
    fun `parseDateToMillis parses common shapes and zeros the rest`() {
        assertTrue(MimeParser.parseDateToMillis("Thu, 21 Oct 2026 18:24:12 +0000") > 0)
        assertTrue(MimeParser.parseDateToMillis("2026-10-21T18:24:12+0000") > 0)
        assertEquals(0L, MimeParser.parseDateToMillis(""))
        assertEquals(0L, MimeParser.parseDateToMillis("!!! not a date !!!"))
        val first = MimeParser.parseDateToMillis("01 Jan 2020 00:00:00 +0000")
        val second = MimeParser.parseDateToMillis("01 Jan 2021 00:00:00 +0000")
        assertTrue(first < second)
    }

    @Test
    fun `parsePart decodes seven bit text with crlf or lf separators`() {
        val crlf = "Subject: Hi\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nhello"
        val part = MimeParser.parsePart(crlf.toByteArray(Charsets.UTF_8), "")
        assertEquals("hello", part.bodyText)
        assertTrue(part.contentType.isText)
        val lf = "Subject: Hi\nContent-Type: text/plain\n\nhello"
        assertEquals("hello", MimeParser.parsePart(lf.toByteArray(Charsets.UTF_8), "").bodyText)
    }

    @Test
    fun `parsePart decodes quoted printable bodies`() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: quoted-printable\r\n\r\nH=69=20there=21"
        val part = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        assertEquals("Hi there!", part.bodyText)
    }

    @Test
    fun `parsePart without a boundary yields no children`() {
        val raw = "Content-Type: multipart/mixed\r\n\r\n--b\r\nx\r\n--b--"
        val part = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        assertTrue(part.children.isEmpty())
        val (body, _, attachments) = MimeParser.collectBodyAndAttachments(part, 1L, "a@b", "INBOX")
        assertNull(body)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `parsePart without a separator keeps headers and an empty body`() {
        val part = MimeParser.parsePart("Subject: Hi\r\nFrom: a@b".toByteArray(), "")
        assertEquals("Hi", part.headers["subject"])
        assertEquals("", part.bodyText)
    }

    @Test
    fun `multipart alternative prefers html over plain`() {
        val raw = "Content-Type: multipart/alternative; boundary=alt\r\n\r\n" +
            "--alt\r\nContent-Type: text/plain\r\n\r\nPlain version\r\n" +
            "--alt\r\nContent-Type: text/html\r\n\r\n<b>Html version</b>\r\n--alt--\r\n"
        val root = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        val (body, isHtml, _) = MimeParser.collectBodyAndAttachments(root, 1L, "a@b", "INBOX")
        assertEquals("<b>Html version</b>", body)
        assertTrue(isHtml)
    }

    @Test
    fun `attachments are recorded with filename and byte size`() {
        val raw = "Content-Type: application/pdf\r\n" +
            "Content-Disposition: attachment; filename=\"report.pdf\"\r\n\r\n12345"
        val root = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        val (body, isHtml, attachments) = MimeParser.collectBodyAndAttachments(root, 7L, "a@b", "INBOX")
        assertNull(body)
        assertFalse(isHtml)
        assertEquals(1, attachments.size)
        assertEquals("report.pdf", attachments[0].fileName)
        assertEquals(5L, attachments[0].size)
    }

    @Test
    fun `inline images with a content id are not attachments`() {
        val raw = "Content-Type: multipart/mixed; boundary=m\r\n\r\n" +
            "--m\r\nContent-Type: image/png\r\nContent-Id: <img1>\r\n" +
            "Content-Disposition: inline; filename=\"i.png\"\r\n\r\nXYZ\r\n--m--\r\n"
        val root = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        val (body, _, attachments) = MimeParser.collectBodyAndAttachments(root, 1L, "a@b", "INBOX")
        assertNull(body)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `empty input yields an empty body and no attachments`() {
        val root = MimeParser.parsePart(ByteArray(0), "")
        assertTrue(root.children.isEmpty())
        val (body, _, attachments) = MimeParser.collectBodyAndAttachments(root, 1L, "a@b", "INBOX")
        assertTrue(body.isNullOrEmpty())
        assertTrue(attachments.isEmpty())
    }
}
