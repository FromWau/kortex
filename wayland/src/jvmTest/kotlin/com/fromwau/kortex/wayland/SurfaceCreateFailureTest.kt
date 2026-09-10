package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.getOrElse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * A surface whose creation fails partway gives back everything it had built.
 *
 * A withdrawn `wl_seat` is the latest failure this machine can produce: by then the shm, the layer surface,
 * both frames, the frame dispatcher, the cursor theme, the cursor surface and the scene all exist.
 */
class SurfaceCreateFailureTest {
    @Test
    fun `a create that fails at the seat gives back what it built and leaves the connection usable`() {
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }

        display.use { wayland ->
            val seat = assertNotNull(wayland.global(WL_SEAT), "the compositor advertised no $WL_SEAT")
            // Exactly what the registry listener does on global_remove; no shell is listening to react to it.
            wayland.removeGlobal(seat.name)

            val memfdsBefore = openMemfds()
            val failed = KortexSurface.create(wayland, CONFIG)
            // libwayland holds a duplicate of each descriptor it sends until the next flush, which is no leak.
            wayland.roundtrip()

            assertEquals(Err(KortexError.MissingGlobal(WL_SEAT)), failed)
            assertEquals(memfdsBefore, openMemfds(), "a failed create left memfd-backed descriptors open")
            assertFalse(NAMESPACE in Hyprctl.namespaces(), "a failed create left its layer surface on the compositor")
            assertNull(wayland.protocolError(), "giving back a failed create's pieces cost the connection")

            wayland.addGlobal(seat)
            KortexSurface.create(wayland, CONFIG)
                .getOrElse { error -> fail("a create on the connection a failed one used did not succeed: $error") }
                .close()
            wayland.roundtrip()
            assertNull(wayland.protocolError(), "the create that followed the failed one cost the connection")
        }
    }

    /** What every descriptor on a memfd points at: each frame's buffer, and a cursor theme's pool. */
    private fun openMemfds(): List<String> = File("/proc/self/fd")
        .listFiles()
        .orEmpty()
        // The descriptor that lists the directory is gone by the time its link is read.
        .mapNotNull { fd -> runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull() }
        .filter { it.startsWith("/memfd:") }
        .sorted()

    private companion object {
        const val WL_SEAT = "wl_seat"
        const val NAMESPACE = "kortex-create-failure"
        const val SIDE = 64

        val CONFIG = SurfaceConfig.osd(SIDE.dp, SIDE.dp).copy(namespace = NAMESPACE)
    }
}
