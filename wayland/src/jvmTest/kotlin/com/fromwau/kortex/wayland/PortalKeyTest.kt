package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What kortex makes of `application/vnd.portal.filetransfer`, the key an application in a sandbox offers in
 * place of paths its own filesystem alone understands.
 *
 * Desktop-free, and it has to be: resolving a key is a D-Bus call kortex does not make, and offering one
 * needs a sandboxed application, which no test here can be. So the drain that reads a key off a real drop is
 * uncovered, the same way `Drag.uriListType`'s was before kortex could drag a file list itself.
 */
class PortalKeyTest {
    @Test
    fun `a key reads back as the token it was sent as`() {
        assertEquals("1234567890", decodePortalKey("1234567890".encodeToByteArray()), "a plain key")
    }

    @Test
    fun `a key a sender terminates reads back without the terminator`() {
        val sent = "1234567890\u0000".encodeToByteArray()

        assertEquals(
            "1234567890", decodePortalKey(sent),
            "a NUL a sender ends its key with reached content as part of the key",
        )
    }

    @Test
    fun `a drag offering a file list and a key names the list first, which is the one kortex can hand over`() {
        withOffer(listOf(PORTAL_TYPE, URI_LIST_TYPE)) { offer ->
            assertEquals(
                listOf(URI_LIST_TYPE, PORTAL_TYPE), offer.offeredTypes.map(Mime::wireName),
                "a key was preferred to the file list beside it, which content would then have to resolve",
            )
        }
    }

    @Test
    fun `a drag carrying a key hands it to content, and carries nothing else`() {
        val offer = KortexDragOffer(PortalMime.entries, portalKey = Ok(KEY))

        assertEquals(listOf(PORTAL_TYPE), offer.types, "the drag did not say it was carrying a key")
        assertEquals(Ok(KEY), offer.readPortalKey(), "the key never reached content")
        assertEquals(Err(ClipboardError.NoUris), offer.readUris(), "a key was handed back as a list of files")
    }

    /** An offer listing [types] in the order given, as the compositor introduces one. */
    private fun <T> withOffer(types: List<String>, block: (DataOffer) -> T): T = Arena.ofShared().use { arena ->
        val offer = DataOffer(arena)
        types.forEach { offer.onOffer(NULL, NULL, arena.allocateFrom(it)) }
        block(offer)
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val KEY = "1234567890"

        // Spelled out rather than read off the enums, so renaming either wire name fails here.
        const val PORTAL_TYPE = "application/vnd.portal.filetransfer"
        const val URI_LIST_TYPE = "text/uri-list"
    }
}
