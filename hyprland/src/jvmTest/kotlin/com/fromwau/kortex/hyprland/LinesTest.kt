package com.fromwau.kortex.hyprland

import kotlin.test.Test
import kotlin.test.assertEquals

class LinesTest {
    @Test
    fun aLineSplitAcrossReadsComesOutWhole() {
        val lines = Lines()

        assertEquals(emptyList(), lines.feed("workspa"))
        assertEquals(
            listOf("workspacev2>>2,2", "focusedmonv2>>DP-1,2"),
            lines.feed("cev2>>2,2\nfocusedmonv2>>DP-1,2\nopen"),
        )
        assertEquals(listOf("openwindow>>a,2,kitty,t"), lines.feed("window>>a,2,kitty,t\n"))
    }

    @Test
    fun aCharacterSplitAcrossReadsIsNotMangled() {
        val lines = Lines()
        val bytes = "windowtitlev2>>a,◐ x\n".encodeToByteArray()
        val cut = bytes.indexOfFirst { it == 0xE2.toByte() } + 1

        assertEquals(emptyList(), lines.add(bytes.copyOfRange(0, cut), cut))
        val rest = bytes.copyOfRange(cut, bytes.size)
        assertEquals(listOf("windowtitlev2>>a,◐ x"), lines.add(rest, rest.size))
    }

    @Test
    fun theNameIsReadWhateverTheDataHoldsOrLacks() {
        assertEquals("renameworkspace", eventName("renameworkspace>>9,a,b"))
        assertEquals("activewindowv2", eventName("activewindowv2>>"))
        assertEquals("configreloaded", eventName("configreloaded>>"))
    }

    private fun Lines.feed(text: String): List<String> {
        val bytes = text.encodeToByteArray()
        return add(bytes, bytes.size)
    }
}
