package com.fromwau.kortex.wayland

import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
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

    private companion object {
        val WAYLAND_DISPLAY: String = System.getenv("WAYLAND_DISPLAY") ?: "<unset>"
        val REQUIRED = listOf("wl_compositor", "wl_shm", "wl_seat", "wl_output", "zwlr_layer_shell_v1")
    }
}
