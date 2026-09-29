package com.fromwau.kortex.wayland

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [decodeUriList], which reads the `text/uri-list` a file manager drags out: RFC 2483's CRLF-separated
 * lines, of which the blank ones and those beginning with `#` are not URIs.
 *
 * Desktop-free: the decode is a function of the bytes alone.
 */
class UriListTest {
    @Test
    fun `a list reads back as its uris in the order they arrived, with no entry for the break that ends it`() {
        val sent = "file:///tmp/one.txt\r\nfile:///tmp/two.txt\r\n"

        assertEquals(
            listOf("file:///tmp/one.txt", "file:///tmp/two.txt"), decodeUriList(sent.encodeToByteArray()),
            "a two-file list did not read back as those two files",
        )
    }

    @Test
    fun `a comment line and a blank line are not uris`() {
        val sent = "# the files you dragged\r\n\r\nfile:///tmp/one.txt\r\n#and a trailing note\r\n"

        assertEquals(
            listOf("file:///tmp/one.txt"), decodeUriList(sent.encodeToByteArray()),
            "a comment or a blank line was read as a file",
        )
    }

    @Test
    fun `a uri keeps the percent-encoding and the scheme it was sent under`() {
        val sent = "file:///tmp/a%20file.txt\r\nhttps://example.org/x?y=1\r\n"

        assertEquals(
            listOf("file:///tmp/a%20file.txt", "https://example.org/x?y=1"), decodeUriList(sent.encodeToByteArray()),
            "a uri was decoded or a scheme dropped, which only the application that sent it can do",
        )
    }

    @Test
    fun `a sender that breaks its lines with a bare newline is read like one sending CRLF`() {
        val sent = "file:///tmp/one.txt\nfile:///tmp/two.txt"

        assertEquals(
            listOf("file:///tmp/one.txt", "file:///tmp/two.txt"), decodeUriList(sent.encodeToByteArray()),
            "a list broken with bare newlines did not read back as its files",
        )
    }

    @Test
    fun `an empty transfer carries no uris rather than one empty uri`() {
        assertEquals(emptyList(), decodeUriList(ByteArray(0)), "an empty uri list read as a file")
    }
}
