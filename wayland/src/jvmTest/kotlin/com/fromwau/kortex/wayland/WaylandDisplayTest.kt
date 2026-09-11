package com.fromwau.kortex.wayland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class WaylandDisplayTest {
    @Test
    fun `enumerates the compositor's globals`() {
        val display = WaylandDisplay.connect()
            .getOrElse { error -> fail("no compositor answered on $WAYLAND_DISPLAY: $error") }

        display.use {
            val globals = it.globals
            globals.sortedBy(WaylandGlobal::interfaceName).forEach { global ->
                println("GLOBAL ${global.interfaceName} v${global.version} (name=${global.name})")
            }

            val names = globals.map(WaylandGlobal::interfaceName)
            assertTrue(globals.isNotEmpty(), "the registry advertised nothing at all")
            REQUIRED.forEach { required ->
                assertTrue(required in names, "compositor did not advertise $required")
            }
            assertTrue(
                names.count { name -> name == "wl_output" } >= 1,
                "expected at least one wl_output",
            )
        }
    }

    @Test
    fun `a display named explicitly connects like the default one`() {
        val name = assertNotNull(System.getenv("WAYLAND_DISPLAY"), "WAYLAND_DISPLAY is unset")
        val display = WaylandDisplay.connect(name)
            .getOrElse { error -> fail("no compositor answered on $name: $error") }

        display.use { assertTrue(it.globals.isNotEmpty(), "the registry on $name advertised nothing") }
    }

    @Test
    fun `a display name nothing listens on fails to connect`() {
        val result = WaylandDisplay.connect(NO_SUCH_DISPLAY)
        result.onSuccess { it.close() }

        assertEquals(Err(KortexError.NoCompositorResponse), result)
    }

    @Test
    fun `closing the connection frees its registry listener`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        val listener = display.registryListener
        assertTrue(listener.scope().isAlive, "the registry listener was freed while its connection was open")

        display.close()
        assertFalse(listener.scope().isAlive, "the registry listener outlived its connection")
    }

    private companion object {
        val WAYLAND_DISPLAY: String = System.getenv("WAYLAND_DISPLAY") ?: "<unset>"
        val REQUIRED = listOf("wl_compositor", "wl_shm", "wl_seat", "wl_output", "zwlr_layer_shell_v1")
        const val NO_SUCH_DISPLAY = "kortex-no-such-display"
    }
}
