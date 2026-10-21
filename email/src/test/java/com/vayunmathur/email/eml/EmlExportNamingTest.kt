package com.vayunmathur.email.eml

import com.vayunmathur.email.network.imap.MimeParser
import com.vayunmathur.email.platform.EmlUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins EML export filenames and attachment display names.
 *
 * Export writes a file the user keeps, so a bad name is not a crash but a
 * lost message: doubled extensions, path separators that escape the folder,
 * or names the filesystem rejects. Vectors below cover each shape.
 */
class EmlExportNamingTest {

    @Test
    fun `plain subjects gain a single eml extension`() {
        assertEquals("hello.eml", EmlUtils.sanitizeFileName("hello"))
        assertEquals("hello.eml", EmlUtils.sanitizeFileName("hello.eml"))
        assertEquals("hello.eml", EmlUtils.sanitizeFileName("hello.EML"))
    }

    @Test
    fun `unsafe characters collapse to single underscores`() {
        assertEquals("a_b_c.eml", EmlUtils.sanitizeFileName("a/b:c"))
        assertEquals("my_mail.eml", EmlUtils.sanitizeFileName("my mail.eml"))
        assertEquals("Report_FINAL.eml", EmlUtils.sanitizeFileName("Report.FINAL"))
    }

    @Test
    fun `blank and degenerate names fall back`() {
        assertEquals("email.eml", EmlUtils.sanitizeFileName(""))
        assertEquals("email.eml", EmlUtils.sanitizeFileName("___"))
        assertEquals("draft.eml", EmlUtils.sanitizeFileName("", fallback = "draft"))
        assertEquals("draft.eml", EmlUtils.sanitizeFileName("...", fallback = "draft"))
    }

    @Test
    fun `long subjects are truncated to a bounded name`() {
        val name = EmlUtils.sanitizeFileName("x".repeat(200))
        assertTrue(name.endsWith(".eml"))
        assertTrue(name.length <= 64)
    }

    @Test
    fun `attachment names drop directories and control characters`() {
        assertEquals("c.txt", MimeParser.sanitizeFilename("a/b\\c.txt"))
        assertEquals("a_b__c", MimeParser.sanitizeFilename("a\"b\r\nc"))
        assertEquals("attachment", MimeParser.sanitizeFilename("   "))
    }

    @Test
    fun `attachment names are capped instead of overflowing`() {
        val name = MimeParser.sanitizeFilename("y".repeat(200))
        assertTrue(name.length <= 120)
    }
}
