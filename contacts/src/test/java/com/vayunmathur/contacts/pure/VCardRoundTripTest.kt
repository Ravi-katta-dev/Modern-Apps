package com.vayunmathur.contacts.pure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VCardRoundTripTest {

    private data class VCardContact(
        val firstName: String = "",
        val lastName: String = "",
        val middleName: String = "",
        val prefix: String = "",
        val suffix: String = "",
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val note: String = "",
        val favorite: Boolean = false,
    )

    private fun escapeV(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun unescapeV(value: String): String {
        return value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";")
            .replace("\\\\", "\\")
    }

    private fun foldLine(line: String): List<String> {
        if (line.length <= 75) return listOf(line)
        val out = mutableListOf<String>()
        var idx = 0
        var first = true
        while (idx < line.length) {
            val end = minOf(idx + 75, line.length)
            val part = line.substring(idx, end)
            out.add(if (first) part else " $part")
            first = false
            idx = end
        }
        return out
    }

    private fun serialize(contact: VCardContact): String {
        val fields = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
        val nameLine = "N:${escapeV(contact.lastName)};${escapeV(contact.firstName)};" +
            "${escapeV(contact.middleName)};${escapeV(contact.prefix)};${escapeV(contact.suffix)}"
        fields.add(nameLine)
        val full = listOf(contact.prefix, contact.firstName, contact.middleName, contact.lastName)
            .plus(contact.suffix)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        fields.add("FN:${escapeV(full)}")
        contact.phones.forEach { fields.add("TEL;TYPE=VOICE:${escapeV(it)}") }
        contact.emails.forEach { fields.add("EMAIL;TYPE=INTERNET:${escapeV(it)}") }
        if (contact.note.isNotEmpty()) fields.add("NOTE:${escapeV(contact.note)}")
        if (contact.favorite) fields.add("X-STARRED:1")
        fields.add("END:VCARD")
        return fields.flatMap(::foldLine).joinToString("\r\n") + "\r\n"
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        var pending: String? = null
        text.split("\r\n", "\n").forEach { raw ->
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                pending = (pending ?: "") + raw.trimStart()
            } else {
                pending?.let { out.add(it) }
                pending = raw
            }
        }
        pending?.let { out.add(it) }
        return out
    }

    private fun parse(text: String): List<VCardContact> {
        val contacts = mutableListOf<VCardContact>()
        var last = ""
        var firstName = ""
        var middle = ""
        var prefix = ""
        var suffix = ""
        var full = ""
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var favorite = false
        var inCard = false
        fun build(): VCardContact {
            var f = firstName
            var l = last
            if (f.isBlank() && l.isBlank() && middle.isBlank() && prefix.isBlank() && suffix.isBlank()) {
                val parts = full.split(" ").filter { it.isNotEmpty() }
                f = parts.firstOrNull() ?: ""
                l = parts.drop(1).joinToString(" ")
            }
            return VCardContact(
                f, l, middle, prefix, suffix, phones.toList(), emails.toList(),
                notes.joinToString("\n"), favorite,
            )
        }
        fun flush() {
            if (inCard) contacts.add(build())
            last = ""
            firstName = ""
            middle = ""
            prefix = ""
            suffix = ""
            full = ""
            phones.clear()
            emails.clear()
            notes.clear()
            favorite = false
            inCard = false
        }
        for (line in unfold(text)) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("BEGIN:VCARD", ignoreCase = true)) {
                inCard = true
                continue
            }
            if (trimmed.startsWith("END:VCARD", ignoreCase = true)) {
                flush()
                continue
            }
            if (!inCard) continue
            val colon = trimmed.indexOf(':')
            if (colon == -1) continue
            val prop = trimmed.take(colon).split(';').firstOrNull()?.uppercase() ?: continue
            val rawValue = trimmed.substring(colon + 1)
            when (prop) {
                "N" -> {
                    val comps = unescapeV(rawValue).split(';')
                    last = comps.getOrNull(0) ?: ""
                    firstName = comps.getOrNull(1) ?: ""
                    middle = comps.getOrNull(2) ?: ""
                    prefix = comps.getOrNull(3) ?: ""
                    suffix = comps.getOrNull(4) ?: ""
                }
                "FN" -> full = unescapeV(rawValue)
                "TEL" -> phones.add(unescapeV(rawValue))
                "EMAIL" -> emails.add(unescapeV(rawValue))
                "NOTE" -> notes.add(unescapeV(rawValue))
                "X-STARRED" -> if (rawValue.trim() == "1") favorite = true
            }
        }
        return contacts
    }

    private fun parseStrict(text: String): List<VCardContact> {
        require(text.contains("BEGIN:VCARD", ignoreCase = true)) { "not a vCard blob" }
        return parse(text)
    }

    private fun roundTrip(contact: VCardContact): VCardContact = parse(serialize(contact)).single()

    @Test
    fun simpleContactRoundTrips() {
        val original = VCardContact(
            firstName = "Ada",
            lastName = "Lovelace",
            phones = listOf("+1 415-555-0132"),
            emails = listOf("ada@example.com"),
            note = "First programmer",
            favorite = true,
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun unicodeNamesAndNotesSurvive() {
        val original = VCardContact(
            firstName = "José",
            lastName = "北京伟",
            middleName = "Émilie",
            note = "Müller — café",
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun commasSemicolonsAndNewlinesAreEscaped() {
        val original = VCardContact(firstName = "Ann;Marie", lastName = "O,Neil", note = "line1\nline2\\end")
        val blob = serialize(original)
        assertTrue(blob.contains("O\\,Neil"), "comma must be escaped")
        assertTrue(blob.contains("Ann\\;Marie"), "semicolon must be escaped")
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun longNoteIsFoldedAndRestored() {
        val long = "n".repeat(70) + ", tail " + "n".repeat(120)
        val blob = serialize(VCardContact(firstName = "Ana", note = long))
        assertTrue(blob.contains("\r\n "), "expected a folded continuation line")
        assertEquals(long, parse(blob).single().note)
    }

    @Test
    fun emptyContactRoundTrips() {
        val back = roundTrip(VCardContact())
        assertEquals("", back.firstName)
        assertEquals("", back.note)
        assertTrue(back.phones.isEmpty())
        assertTrue(back.emails.isEmpty())
        assertFalse(back.favorite)
    }

    @Test
    fun invalidPhoneStringsSurviveVerbatim() {
        val weird = listOf("not-a-phone", "123", "+-() ", "12")
        val back = roundTrip(VCardContact(firstName = "Zed", phones = weird))
        assertEquals(weird, back.phones)
    }

    @Test
    fun phoneAndEmailOrderIsPreserved() {
        val original = VCardContact(
            firstName = "Cy",
            phones = listOf("111", "222"),
            emails = listOf("a@x.com", "b@x.com"),
        )
        val back = roundTrip(original)
        assertEquals(listOf("111", "222"), back.phones)
        assertEquals(listOf("a@x.com", "b@x.com"), back.emails)
    }

    @Test
    fun twoContactsShareOneBlob() {
        val blob = serialize(VCardContact(firstName = "Ada")) + serialize(VCardContact(firstName = "Bob"))
        val parsed = parse(blob)
        assertEquals(2, parsed.size)
        assertEquals("Ada", parsed[0].firstName)
        assertEquals("Bob", parsed[1].firstName)
    }

    @Test
    fun linesWithoutColonAreSkipped() {
        val blob = "BEGIN:VCARD\r\nVERSION:3.0\r\nGARBAGE-LINE\r\nFN:Kim\r\nEND:VCARD\r\n"
        val parsed = parse(blob).single()
        assertEquals("Kim", parsed.firstName)
        assertTrue(parsed.phones.isEmpty())
    }

    @Test
    fun fnSuppliesNameWhenNIsEmpty() {
        val blob = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Madonna\r\nEND:VCARD\r\n"
        val parsed = parse(blob).single()
        assertEquals("Madonna", parsed.firstName)
        assertEquals("", parsed.lastName)
    }

    @Test
    fun blobWithoutBeginThrows() {
        assertFailsWith<IllegalArgumentException> { parseStrict("FN:No Card Here") }
    }
}
