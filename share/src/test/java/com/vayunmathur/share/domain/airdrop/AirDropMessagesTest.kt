package com.vayunmathur.share.domain.airdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDropMessagesTest {

    @Test
    fun `ask round trips through a binary plist`() {
        val ask = AirDropMessages.AskRequest(
            senderComputerName = "Pixel",
            senderModelName = "Pixel 8",
            senderId = "0a1b2c3d4e5f",
            files = listOf(AirDropMessages.AskFile.forFile("IMG_1.jpg", 1234)),
            transferId = "uuid-1",
        )
        val parsed = AirDropMessages.AskRequest.fromPlist(BinaryPlist.decodeDict(BinaryPlist.encode(ask.toPlist())))
        assertEquals(ask, parsed)
        assertEquals("public.jpeg", parsed!!.files.single().fileType)
        assertEquals("./IMG_1.jpg", parsed.files.single().fileBomPath)
    }

    @Test
    fun `parses an iOS style ask without transfer id`() {
        val plist = mapOf(
            "SenderComputerName" to "Vayun’s iPhone",
            "SenderModelName" to "iPhone17,1",
            "SenderID" to "d1e2f3a4b5c6",
            "BundleID" to "com.apple.mobileslideshow",
            "SenderRecordData" to ByteArray(10),
            "ConvertMediaFormats" to false,
            "Files" to listOf(
                mapOf(
                    "FileName" to "IMG_0001.HEIC",
                    "FileType" to "public.heic",
                    "FileBomPath" to "./IMG_0001.HEIC",
                    "FileIsDirectory" to false,
                    "ConvertMediaFormats" to true,
                ),
            ),
        )
        val ask = AirDropMessages.AskRequest.fromPlist(plist)!!
        assertNull(ask.transferId)
        assertEquals(-1L, ask.files.single().fileSize)
        assertEquals(0L, ask.totalBytes)
        assertEquals("Vayun’s iPhone", ask.senderComputerName)
    }

    @Test
    fun `an ask with nothing in it is rejected`() {
        assertNull(AirDropMessages.AskRequest.fromPlist(mapOf("SenderComputerName" to "x")))
    }

    @Test
    fun `discover response requires a name`() {
        val resp = AirDropMessages.DiscoverResponse("Pixel", AirDropMessages.MANUFACTURER_GOOGLE)
        assertEquals("Pixel", AirDropMessages.DiscoverResponse.fromPlist(resp.toPlist())?.receiverComputerName)
        assertNull(AirDropMessages.DiscoverResponse.fromPlist(mapOf("ReceiverComputerName" to "")))
    }

    @Test
    fun `archive paths are confined to the destination`() {
        assertEquals("a.jpg", AirDropMessages.safeRelativePath("./a.jpg"))
        assertEquals("dir/b.txt", AirDropMessages.safeRelativePath("./NSIRD_AirDrop_123/dir/b.txt"))
        assertNull(AirDropMessages.safeRelativePath("./../etc/passwd"))
        assertNull(AirDropMessages.safeRelativePath("./"))
        assertEquals("abs", AirDropMessages.safeRelativePath("/abs"))
    }

    @Test
    fun `short id is 12 hex chars`() {
        val id = AirDropMessages.randomShortId()
        assertEquals(12, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotNull(Uti.mimeTypeOf("public.jpeg"))
    }
}
