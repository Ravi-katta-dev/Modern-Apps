package com.vayunmathur.everysync.core.format

import com.vayunmathur.everysync.core.data.RemoteContact
import com.vayunmathur.everysync.core.data.TypedValue

/**
 * Platform type codes for contact fields. Values mirror
 * `android.provider.ContactsContract.CommonDataKinds` ints so the Android
 * `actual` can pass them through unchanged:
 * - Phone: MOBILE=2, WORK=3, HOME=1, FAX_HOME=4, FAX_WORK=5, OTHER=7
 * - Email: WORK=2, HOME=1, OTHER=3
 * - Postal: WORK=2, HOME=1, OTHER=3
 */
enum class PhoneKind(val code: Int) {
    HOME(1),
    MOBILE(2),
    WORK(3),
    FAX_HOME(4),
    FAX_WORK(5),
    OTHER(7),
}

enum class EmailKind(val code: Int) {
    HOME(1),
    WORK(2),
    OTHER(3),
}

enum class PostalKind(val code: Int) {
    HOME(1),
    WORK(2),
    OTHER(3),
}

/**
 * Minimal vCard 3.0/4.0 reader/writer covering the fields EverySync maps into
 * platform contact stores. Self-contained.
 *
 * KMP port of the Android `VCard`: `ContactsContract` type-int constants
 * replaced with [PhoneKind]/[EmailKind]/[PostalKind] (same numeric values).
 */
object VCard {

    fun parse(text: String, fallbackUid: String? = null): RemoteContact {
        val builder = VCardBuilder(fallbackUid ?: "")
        for (line in unfold(text)) {
            val prop = splitProperty(line) ?: continue
            builder.apply(prop)
        }
        return builder.build()
    }

    private class VCardBuilder(private var uid: String) {
        private var fn = ""
        private var prefix = ""
        private var first = ""
        private var middle = ""
        private var last = ""
        private var suffix = ""
        private var org = ""
        private var note = ""
        private var bday: String? = null
        private val phones = mutableListOf<TypedValue>()
        private val emails = mutableListOf<TypedValue>()
        private val addresses = mutableListOf<TypedValue>()

        fun apply(prop: Prop) {
            when (prop.name.uppercase()) {
                "UID" -> uid = prop.value.ifBlank { uid }
                "FN" -> fn = unescape(prop.value)
                "N" -> readName(prop.value)
                "ORG" -> org = unescape(prop.value.split(";").firstOrNull() ?: prop.value)
                "NOTE" -> note = unescape(prop.value)
                "BDAY" -> bday = normalizeDate(prop.value)
                "TEL" -> phones += TypedValue(unescape(prop.value), phoneType(prop.params).code)
                "EMAIL" -> emails += TypedValue(unescape(prop.value), emailType(prop.params).code)
                "ADR" -> addresses += TypedValue(formatAddress(prop.value), addressType(prop.params).code)
            }
        }

        private fun readName(value: String) {
            val parts = value.split(";")
            last = unescape(parts.getOrElse(0) { "" })
            first = unescape(parts.getOrElse(1) { "" })
            middle = unescape(parts.getOrElse(2) { "" })
            prefix = unescape(parts.getOrElse(3) { "" })
            suffix = unescape(parts.getOrElse(4) { "" })
        }

        fun build(): RemoteContact {
            val display = fn.ifBlank {
                listOf(prefix, first, middle, last, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            return RemoteContact(
                uid = uid,
                displayName = display,
                prefix = prefix,
                firstName = first,
                middleName = middle,
                lastName = last,
                suffix = suffix,
                organization = org,
                note = note,
                phones = phones,
                emails = emails,
                addresses = addresses,
                birthday = bday,
            )
        }
    }

    fun serialize(c: RemoteContact): String = buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        append("UID:${c.uid}\r\n")
        append("N:${esc(c.lastName)};${esc(c.firstName)};${esc(c.middleName)};${esc(c.prefix)};${esc(c.suffix)}\r\n")
        append("FN:${esc(c.displayName)}\r\n")
        if (c.organization.isNotBlank()) append("ORG:${esc(c.organization)}\r\n")
        for (p in c.phones) append("TEL;TYPE=${phoneTypeName(p.type)}:${esc(p.value)}\r\n")
        for (e in c.emails) append("EMAIL;TYPE=${emailTypeName(e.type)}:${esc(e.value)}\r\n")
        for (a in c.addresses) append("ADR;TYPE=${addressTypeName(a.type)}:;;${esc(a.value)};;;;\r\n")
        c.birthday?.let { append("BDAY:$it\r\n") }
        if (c.note.isNotBlank()) append("NOTE:${esc(c.note)}\r\n")
        append("END:VCARD\r\n")
    }

    // --- helpers ---

    /** RFC 6350 line unfolding: continuation lines start with space/tab. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.trimStart()
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private data class Prop(val name: String, val params: String, val value: String)

    private fun splitProperty(line: String): Prop? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val left = line.take(colon)
        val value = line.substring(colon + 1)
        val semi = left.indexOf(';')
        return if (semi > 0) Prop(left.take(semi), left.substring(semi + 1), value)
        else Prop(left, "", value)
    }

    private fun paramTypes(params: String): List<String> =
        params.split(";")
            .flatMap { it.removePrefix("TYPE=").removePrefix("type=").split(",") }
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

    private fun phoneType(params: String): PhoneKind = when {
        paramTypes(params).any { it == "CELL" || it == "MOBILE" } -> PhoneKind.MOBILE
        paramTypes(params).contains("WORK") -> PhoneKind.WORK
        paramTypes(params).contains("HOME") -> PhoneKind.HOME
        paramTypes(params).contains("FAX") -> PhoneKind.FAX_HOME
        else -> PhoneKind.OTHER
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        PhoneKind.MOBILE.code -> "CELL"
        PhoneKind.WORK.code -> "WORK"
        PhoneKind.HOME.code -> "HOME"
        PhoneKind.FAX_HOME.code, PhoneKind.FAX_WORK.code -> "FAX"
        else -> "VOICE"
    }

    private fun emailType(params: String): EmailKind = when {
        paramTypes(params).contains("WORK") -> EmailKind.WORK
        paramTypes(params).contains("HOME") -> EmailKind.HOME
        else -> EmailKind.OTHER
    }

    private fun emailTypeName(type: Int): String = when (type) {
        EmailKind.WORK.code -> "WORK"
        EmailKind.HOME.code -> "HOME"
        else -> "INTERNET"
    }

    private fun addressType(params: String): PostalKind = when {
        paramTypes(params).contains("WORK") -> PostalKind.WORK
        paramTypes(params).contains("HOME") -> PostalKind.HOME
        else -> PostalKind.OTHER
    }

    private fun addressTypeName(type: Int): String = when (type) {
        PostalKind.WORK.code -> "WORK"
        PostalKind.HOME.code -> "HOME"
        else -> "OTHER"
    }

    /** ADR is ;;street;city;region;code;country — flatten to a display string. */
    private fun formatAddress(value: String): String =
        value.split(";").map { unescape(it) }.filter { it.isNotBlank() }.joinToString(", ")

    private fun normalizeDate(value: String): String? {
        val v = value.trim()
        return when {
            v.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> v
            v.matches(Regex("\\d{8}")) -> "${v.take(4)}-${v.substring(4, 6)}-${v.substring(6, 8)}"
            else -> v.takeIf { it.isNotBlank() }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun unescape(s: String): String =
        s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
