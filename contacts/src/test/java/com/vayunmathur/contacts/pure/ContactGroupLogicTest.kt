package com.vayunmathur.contacts.pure

import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.ui.contactGroupsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins contact group membership and account-type logic: group lookup by
 * id with blank-name filtering, account-key encoding, SIM/local account
 * classification and deterministic SIM synthetic ids.
 *
 * contactGroupsOf and the SimContacts helpers are pure and called
 * directly; the ViewModel's key encoding is mirrored (it is private).
 */
class ContactGroupLogicTest {
    private fun memberOf(vararg groupIds: Long): Contact =
        Contact(
            1, null, null, false,
            ContactDetails.empty().copy(groups = groupIds.map { GroupMembership(0, it) }),
        )

    private fun accountKey(type: String?, name: String?): String = "${type ?: ""}|${name ?: ""}"

    private fun encodedAccountKey(type: String?, name: String?): String {
        fun enc(raw: String): String =
            raw.replace("%", "%25").replace("|", "%7C").replace(",", "%2C")
        return "${enc(type ?: "")}|${enc(name ?: "")}"
    }

    @Test
    fun groupsOfReturnsOnlyMemberGroups() {
        val all = listOf(ContactGroup(1, "Friends"), ContactGroup(2, "Family"), ContactGroup(3, "Work"))
        assertEquals(listOf(ContactGroup(1, "Friends")), contactGroupsOf(memberOf(1), all))
    }

    @Test
    fun groupsWithBlankNamesAreExcluded() {
        val all = listOf(ContactGroup(1, ""), ContactGroup(2, "  "), ContactGroup(3, "Kept"))
        assertEquals(listOf(ContactGroup(3, "Kept")), contactGroupsOf(memberOf(1, 2, 3), all))
    }

    @Test
    fun contactWithNoGroupsGetsNone() {
        val all = listOf(ContactGroup(1, "Friends"))
        assertEquals(emptyList(), contactGroupsOf(memberOf(), all))
    }

    @Test
    fun unknownGroupIdsAreIgnored() {
        val all = listOf(ContactGroup(1, "Friends"))
        assertEquals(emptyList(), contactGroupsOf(memberOf(99), all))
    }

    @Test
    fun accountKeyJoinsTypeAndName() {
        assertEquals("google|x", accountKey("google", "x"))
        assertEquals("|", accountKey(null, null))
        assertEquals("t|", accountKey("t", null))
    }

    @Test
    fun encodedKeyEscapesSeparatorChars() {
        assertEquals("a%7Cb%2Cc%25d|n", encodedAccountKey("a|b,c%d", "n"))
    }

    @Test
    fun encodedKeyMatchesPlainKeyWhenNoSpecials() {
        assertEquals(accountKey("google", "x"), encodedAccountKey("google", "x"))
    }

    @Test
    fun simAccountTypeMatchesOnlySimType() {
        assertTrue(isSimAccountType("com.vayunmathur.contacts.sim"))
        assertFalse(isSimAccountType("com.google"))
        assertFalse(isSimAccountType(null))
        assertFalse(isSimAccountType(""))
    }

    @Test
    fun localAccountTypeCoversNullBlankAndLocal() {
        assertTrue(isLocalAccountType(null))
        assertTrue(isLocalAccountType(""))
        assertTrue(isLocalAccountType("com.vayunmathur.contacts.local"))
        assertFalse(isLocalAccountType("com.google"))
        assertFalse(isLocalAccountType("com.vayunmathur.contacts.sim"))
    }

    @Test
    fun defaultLocalAccountNeedsBothBlank() {
        assertTrue(isDefaultLocalAccount(null, null))
        assertTrue(isDefaultLocalAccount("", ""))
        assertFalse(isDefaultLocalAccount("name", null))
        assertFalse(isDefaultLocalAccount(null, "type"))
    }

    @Test
    fun syntheticIdsAreNegativeStableAndDistinct() {
        val a = SimContact(name = "Ann", number = "111")
        val b = SimContact(name = "Bob", number = "222")
        val idA1 = SimContactsDataSource.syntheticIdFor(a)
        val idA2 = SimContactsDataSource.syntheticIdFor(a.copy(subscriptionId = 5))
        assertTrue(idA1 < 0)
        assertEquals(idA1, SimContactsDataSource.syntheticIdFor(a))
        assertTrue(idA2 != idA1, "subscription must feed the hash")
        assertTrue(SimContactsDataSource.syntheticIdFor(b) != idA1)
    }

    @Test
    fun slotNumbersAreOneBased() {
        val info = SimContactsDataSource.SimSubscriptionInfo(5, 0, null, null)
        assertEquals(1, SimContactsDataSource.slotNumberFor(info))
        assertEquals(2, SimContactsDataSource.slotNumberFor(info.copy(slotIndex = 1)))
        assertEquals(1, SimContactsDataSource.slotNumberFor(info.copy(slotIndex = -1)))
    }

    @Test
    fun simAccountNameKeysOnSubscriptionId() {
        val info = SimContactsDataSource.SimSubscriptionInfo(7, 0, "SIM 1", null)
        assertEquals("7", SimContactsDataSource.accountNameFor(info))
        val sim = SimContact(subscriptionId = 7, name = "A", number = "1")
        assertEquals("7", SimContactsDataSource.accountNameForSimContact(sim))
        assertEquals("", SimContactsDataSource.accountNameForSimContact(SimContact(name = "A", number = "1")))
    }
}
