package com.fromwau.kortex.wayland

import androidx.compose.ui.input.key.Key
import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Names keys under keymaps `xkbcli` compiles, each with one of its layouts locked, so layouts the compositor's
 * keyboard does not use are tested without switching it.
 */
class LatinFallbackTest {
    @Test
    fun `a key under a Cyrillic layout is named after the Latin layout before it`() {
        assertEquals(Key.C, keyUnder("us,ru", "ru", KEY_C), "C under ru in us,ru")
    }

    @Test
    fun `a key under a Cyrillic layout is named after the Latin layout after it`() {
        assertEquals(Key.C, keyUnder("ru,us", "ru", KEY_C), "C under ru in ru,us")
    }

    @Test
    fun `a key under a Greek layout is named after the Latin layout`() {
        assertEquals(Key.C, keyUnder("gr,us", "gr", KEY_C), "C under gr in gr,us")
    }

    @Test
    fun `a key under an Arabic layout is named after the Latin layout`() {
        assertEquals(Key.C, keyUnder("ara,us", "ara", KEY_C), "C under ara in ara,us")
    }

    @Test
    fun `the first Latin layout names the keys, not a later one`() {
        // German has Z on the key US calls Y.
        assertEquals(Key.Z, keyUnder("ru,de,us", "ru", KEY_Y), "Y under ru in ru,de,us")
    }

    @Test
    fun `a punctuation key under a Cyrillic layout is named after the Latin layout`() {
        // Under ru this key types a period.
        assertEquals(Key.Slash, keyUnder("us,ru", "ru", KEY_SLASH), "/ under ru in us,ru")
    }

    @Test
    fun `a key only the first layout defines keeps its own name`() {
        assertEquals(Key.Escape, keyUnder("ru,us", "ru", KEY_ESC), "Escape under ru in ru,us")
    }

    @Test
    fun `a key the Latin layout lacks keeps its own layout's name, not an earlier layout's`() {
        // Here ru has slash on this key and gr has guillemotleft; us, the Latin layout, has nothing.
        assertEquals(Key.Unknown, keyUnder("ru,gr,us", "gr", KEY_102ND), "the 102nd key under gr in ru,gr,us")
    }

    @Test
    fun `a key under a Cyrillic layout still types its Cyrillic letter`() {
        assertEquals(CYRILLIC_SMALL_ES, codePointUnder("us,ru", "ru", KEY_C), "what C types under ru in us,ru")
    }

    @Test
    fun `a Latin layout keeps its own name for a key another Latin layout names differently`() {
        // German has ü on the key US calls [.
        assertEquals(Key.Unknown, keyUnder("us,de", "de", KEY_LEFTBRACE), "[ under de in us,de")
    }

    @Test
    fun `AZERTY names the key QWERTY calls Q as A`() {
        assertEquals(Key.A, keyUnder("us,fr", "fr", KEY_Q), "Q under fr in us,fr")
    }

    @Test
    fun `with no Latin layout in the keymap a Cyrillic letter stays unnamed`() {
        assertEquals(Key.Unknown, keyUnder("ru", "ru", KEY_C), "C under ru alone")
    }

    private fun keyUnder(layouts: String, active: String, waylandKey: Int): Key =
        underLayout(layouts, active) { state -> Xkb.key(state, waylandKey) }

    private fun codePointUnder(layouts: String, active: String, waylandKey: Int): Int =
        underLayout(layouts, active) { state -> Xkb.codePoint(state, waylandKey) }

    /** Runs [read] on the keymap for [layouts] with [active] locked, as `wl_keyboard.modifiers` locks a layout. */
    private fun <T> underLayout(layouts: String, active: String, read: (XkbState) -> T): T {
        val group = layouts.split(",").indexOf(active)
        require(group >= 0) { "$active is not one of $layouts" }
        val keymap = Xkbcli.compileKeymap(layouts)
        val state = Arena.ofConfined().use { arena -> Xkb.stateFromKeymap(arena.allocateFrom(keymap)) }
        assertNotNull(state, "xkb could not compile the $layouts keymap")
        try {
            Xkb.updateMask(state, 0, 0, 0, group)
            return read(state)
        } finally {
            Xkb.releaseState(state)
        }
    }

    private companion object {
        // What ru's C types: U+0441, CYRILLIC SMALL LETTER ES.
        const val CYRILLIC_SMALL_ES = 0x441

        // linux/input-event-codes.h
        const val KEY_ESC = 1
        const val KEY_Q = 16
        const val KEY_Y = 21
        const val KEY_LEFTBRACE = 26
        const val KEY_C = 46
        const val KEY_SLASH = 53
        const val KEY_102ND = 86
    }
}
