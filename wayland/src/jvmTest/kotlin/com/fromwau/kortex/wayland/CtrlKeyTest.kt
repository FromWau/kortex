package com.fromwau.kortex.wayland

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import com.fromwau.kortex.compose.KortexTextInput
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.skia.Surface
import java.lang.foreign.MemorySegment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Types with Ctrl held under layouts of its own, each compiled by `xkbcli` and handed over on a memfd as a
 * compositor sends one. It binds no seat and places no surface, so the compositor's own keyboard is untouched.
 */
class CtrlKeyTest {
    @Test
    fun `Ctrl commits nothing under ru with us configured beside it`() {
        assertCtrlTypesNothing(layouts = US_RU, activeLayout = SECOND, typedAlone = RU_ZHE)
    }

    @Test
    fun `Ctrl commits nothing under de with us configured beside it`() {
        assertCtrlTypesNothing(layouts = US_DE, activeLayout = SECOND, typedAlone = DE_O_DIAERESIS)
    }

    @Test
    fun `Ctrl commits nothing under ru alone`() {
        assertCtrlTypesNothing(layouts = RU, activeLayout = FIRST, typedAlone = RU_ZHE)
    }

    @Test
    fun `Ctrl commits nothing under de alone`() {
        assertCtrlTypesNothing(layouts = DE, activeLayout = FIRST, typedAlone = DE_O_DIAERESIS)
    }

    @Test
    fun `Ctrl commits nothing under us alone`() {
        assertCtrlTypesNothing(layouts = US, activeLayout = FIRST, typedAlone = US_SEMICOLON)
    }

    @Test
    fun `a key at level 3 types its character with Ctrl held`() {
        withTextField { typist ->
            typist.useKeymap(compiledKeymap(DE), activeLayout = FIRST)
            typist.holding(CTRL_MASK or LEVEL_THREE_MASK) { typist.tap(KEY_COMMA) }

            assertEquals(DE_MIDDLE_DOT, typist.typed, "AltGr's own character did not reach the text field")
        }
    }

    @Test
    fun `Ctrl and AltGr commit nothing where level 3 carries no character of its own`() {
        assertCtrlAtLevelThreeTypesNothing(key = KEY_SEMICOLON, typedAlone = DE_O_DIAERESIS)
    }

    @Test
    fun `Ctrl and AltGr commit nothing where the character is another layout's`() {
        assertCtrlAtLevelThreeTypesNothing(key = KEY_COMMA, typedAlone = DE_COMMA)
    }

    /**
     * Types the key `us` calls `;`, which types [typedAlone] under [layouts]. It must type that character on
     * its own, and nothing at all with Ctrl held.
     */
    private fun assertCtrlTypesNothing(layouts: String, activeLayout: Int, typedAlone: String) {
        withTextField { typist ->
            typist.useKeymap(compiledKeymap(layouts), activeLayout)
            typist.tap(KEY_SEMICOLON)
            assertEquals(typedAlone, typist.typed, "the key did not type its own character under $layouts")

            typist.holding(CTRL_MASK) { typist.tap(KEY_SEMICOLON) }

            assertEquals(typedAlone, typist.typed, "Ctrl and that key typed into the text field under $layouts")
        }
    }

    /**
     * Types [key] under `us,de` with `de` active, where Ctrl makes xkb report a character `us` has on the key
     * in place of the one `de`'s own level 3 carries. It must type [typedAlone] on its own, and nothing at all
     * with Ctrl and AltGr held.
     */
    private fun assertCtrlAtLevelThreeTypesNothing(key: Int, typedAlone: String) {
        withTextField { typist ->
            typist.useKeymap(compiledKeymap(US_DE), activeLayout = SECOND)
            typist.tap(key)
            assertEquals(typedAlone, typist.typed, "the key did not type its own character under $US_DE")

            typist.holding(CTRL_MASK or LEVEL_THREE_MASK) { typist.tap(key) }

            assertEquals(typedAlone, typist.typed, "Ctrl, AltGr and that key typed into the text field")
        }
    }

    /** Runs [block] against a keyboard with no seat behind it, typing into a focused text field. */
    private fun withTextField(block: (Typist) -> Unit) {
        val typed = AtomicReference("")
        val open = AtomicReference<KortexTextInput?>(null)
        val platform = object : KortexPlatform {
            override fun onTextInputStarted(session: KortexTextInput) = open.set(session)
            override fun onTextInputStopped() = open.set(null)
        }
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "kortex-ctrl-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val surface = Surface.makeRasterN32Premul(SIDE, SIDE)

        dispatcher.use {
            KortexScene(
                size = IntSize(SIDE, SIDE),
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                frameContext = dispatcher,
                onInvalidate = {},
                platform = platform,
            ).use { scene ->
                scene.setContent {
                    val requester = remember { FocusRequester() }
                    RecordingTextField(Modifier.focusRequester(requester), typed)
                    LaunchedEffect(Unit) { requester.requestFocus() }
                }
                // A few frames so the LaunchedEffect runs and focus settles.
                repeat(FOCUS_FRAMES) { frame ->
                    scene.render(surface.canvas.asComposeCanvas(), frame.toLong())
                    Thread.sleep(FRAME_MILLIS)
                }

                block(Typist(KeyboardInput(scene, textInput = { open.get() }), scene, surface, typed))
            }
        }
    }

    @Composable
    private fun RecordingTextField(focus: Modifier, typed: AtomicReference<String>) {
        // Compose state, not the AtomicReference: an unobservable value never recomposes, so every edit
        // would be applied to a stale one.
        var text by remember { mutableStateOf("") }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                typed.set(it)
            },
            modifier = focus,
        )
    }

    /** Presses keys the way a compositor reports them, rendering after each so the composition catches up. */
    private class Typist(
        private val keyboard: KeyboardInput,
        private val scene: KortexScene,
        private val surface: Surface,
        private val fieldText: AtomicReference<String>,
    ) {
        private var serial = 0

        // The layout every modifier change keeps locked, as wl_keyboard.modifiers' group carries it.
        private var group = FIRST

        /** Everything the text field holds so far. */
        val typed: String get() = fieldText.get()

        fun tap(code: Int) {
            keyboard.onKey(NULL, NULL, ++serial, 0, code, PRESSED)
            keyboard.onKey(NULL, NULL, ++serial, 1, code, RELEASED)
            scene.render(surface.canvas.asComposeCanvas(), RENDER_NANOS)
            Thread.sleep(FRAME_MILLIS)
        }

        /** Holds [modifiers] as a compositor reports them: as modifier state, not as keys. */
        fun holding(modifiers: Int, block: () -> Unit) {
            keyboard.onModifiers(NULL, NULL, ++serial, modifiers, 0, 0, group)
            block()
            keyboard.onModifiers(NULL, NULL, ++serial, 0, 0, 0, group)
        }

        /** Hands [keymap] over as a compositor sends one, and keeps [activeLayout] locked through every key. */
        fun useKeymap(keymap: (KeyboardInput) -> Unit, activeLayout: Int) {
            keymap(keyboard)
            assertTrue(keyboard.hasKeymap, "the keyboard could not compile the keymap it was handed")
            group = activeLayout
            keyboard.onModifiers(NULL, NULL, ++serial, 0, 0, 0, group)
        }
    }

    private companion object {
        val NULL: MemorySegment = MemorySegment.NULL
        const val SIDE = 200
        const val FOCUS_FRAMES = 6
        const val FRAME_MILLIS = 60L
        const val RENDER_NANOS = 100L
        const val PRESSED = 1
        const val RELEASED = 0

        const val US_RU = "us,ru"
        const val US_DE = "us,de"
        const val RU = "ru"
        const val DE = "de"
        const val US = "us"

        // The layout wl_keyboard.modifiers locks as its group, counted as the keymap lists them.
        const val FIRST = 0
        const val SECOND = 1

        // What the key us calls `;` types under each layout, and what the comma key types under de, with
        // AltGr held and without.
        const val RU_ZHE = "ж"
        const val DE_O_DIAERESIS = "ö"
        const val US_SEMICOLON = ";"
        const val DE_COMMA = ","
        const val DE_MIDDLE_DOT = "·"

        // Control's and level 3's bits in wl_keyboard.modifiers.
        const val CTRL_MASK = 1 shl 2
        const val LEVEL_THREE_MASK = 1 shl 7

        // linux/input-event-codes.h
        const val KEY_SEMICOLON = 39
        const val KEY_COMMA = 51
    }
}
