package com.fromwau.kortex.wayland

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fromwau.kern.result.getOrElse
import com.fromwau.kortex.compose.LocalKortexSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Aims a surface at a chosen output with [OutputTarget.NamedOutput], against a real compositor.
 *
 * The headless output is created before [WaylandDisplay.connect], so it is one of the outputs
 * [KortexShell.create] binds and rounds-trips before placing anything, the path a `NamedOutput` at
 * startup depends on, since the compositor has not yet named a freshly bound output otherwise.
 */
class NamedOutputTest {
    @Test
    fun `a surface named for a chosen output appears under that output and no other`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                // Named first: after a panel, its own dispatch could mask a missing roundtrip by coincidence.
                val shell = KortexShell.create(wayland, namedSpec(outputName), panelSpec())
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported a $NAMED_NAMESPACE- namespace")

                    val namespace = assertNotNull(namedNamespace(), "the named surface's namespace vanished mid-check")
                    assertEquals(
                        setOf(outputName), monitorsShowing(namespace),
                        "the named surface is not under exactly the output it named",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    @Test
    fun `removing the named output drops its surface and leaves the other panels standing`() {
        var pending: String? = null
        try {
            val outputName = Hyprctl.createHeadlessOutput()
            pending = outputName
            val expectedPanels = Hyprctl.monitorNames().size

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                // Named first: after a panel, its own dispatch could mask a missing roundtrip by coincidence.
                val shell = KortexShell.create(wayland, namedSpec(outputName), panelSpec())
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported a $NAMED_NAMESPACE- namespace")
                    val panelsBefore = awaitPanelCount(expectedPanels)
                    assertEquals(expectedPanels, panelsBefore.size, "expected one panel per connected output")

                    Hyprctl.removeHeadlessOutput(outputName)
                    pending = null

                    val dropped = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() == null }
                    assertTrue(dropped, "the named surface outlived the output it was placed on")
                    assertNull(namedNamespace(), "hyprctl layers still reports a $NAMED_NAMESPACE- namespace")

                    val remainingPanels = awaitPanelCount(expectedPanels - 1)
                    assertEquals(
                        expectedPanels - 1, remainingPanels.size,
                        "a panel on a remaining output disappeared along with the named surface's own output",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    @Test
    fun `a hotplugged output whose predicted name matches the standing spec gets the named surface`() {
        var pending: String? = null
        try {
            val probe = Hyprctl.createHeadlessOutput()
            Hyprctl.removeHeadlessOutput(probe)
            val predictedName = nextHeadlessName(probe)

            val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
            display.use { wayland ->
                val shell = KortexShell.create(wayland, namedSpec(predictedName))
                    .getOrElse { error -> fail("shell creation failed: $error") }

                shell.use {
                    pending = Hyprctl.createHeadlessOutput()
                    assertEquals(
                        predictedName, pending,
                        "Hyprland's headless output naming scheme changed: expected the next HEADLESS-N " +
                            "after $probe was removed, so the prediction this test relies on no longer holds",
                    )

                    val appeared = shell.pump(PUMP_TIMEOUT_MILLIS) { namedNamespace() != null }
                    assertTrue(appeared, "hyprctl layers never reported a $NAMED_NAMESPACE- namespace after hotplug")

                    val namespace = assertNotNull(namedNamespace(), "the named surface's namespace vanished mid-check")
                    assertEquals(
                        setOf(predictedName), monitorsShowing(namespace),
                        "the named surface is not under exactly the output it named",
                    )
                }
            }
        } finally {
            // Guarantees the virtual output never survives a failed assertion above.
            pending?.let(Hyprctl::removeHeadlessOutput)
        }
    }

    @Test
    fun `a shell whose only spec names an absent output waits, then stops once it is drawn and closed`() {
        var pendingOutput: String? = null
        val display = WaylandDisplay.connect().getOrElse { error -> fail("no compositor answered: $error") }
        var shell: KortexShell? = null
        var loopThread: Thread? = null

        try {
            val probe = Hyprctl.createHeadlessOutput()
            Hyprctl.removeHeadlessOutput(probe)
            val predictedName = nextHeadlessName(probe)
            val closeRequested = mutableStateOf(false)

            val created = KortexShell.create(display, namedClosableSpec(predictedName, closeRequested))
                .getOrElse { error -> fail("shell creation failed: $error") }
            shell = created
            assertEquals(0, created.activeSurfaces.size, "a NamedOutput spec for an absent output must place nothing")

            // Only the loop thread may touch libwayland (constraint 1), so this thread runs the whole
            // lifecycle: waiting, placing the hotplugged surface, and returning once it closes itself.
            val thread = Thread(created::runEventLoop, "kortex-named-output-await-test").apply { isDaemon = true }
            loopThread = thread
            thread.start()

            Thread.sleep(LOOP_ALIVE_CHECK_MILLIS)
            assertTrue(
                thread.isAlive,
                "the event loop exited even though its only spec is still waiting for its own output to connect",
            )

            pendingOutput = Hyprctl.createHeadlessOutput()
            assertEquals(
                predictedName, pendingOutput,
                "Hyprland's headless output naming scheme changed: expected the next HEADLESS-N " +
                    "after $probe was removed, so the prediction this test relies on no longer holds",
            )

            assertTrue(
                awaitNamedNamespace(present = true),
                "hyprctl layers never reported a $NAMED_NAMESPACE- namespace after hotplug",
            )

            // Only a Compose state write here: the surface's own close() hop, like KortexHost.open(),
            // queues onto the loop thread instead of touching libwayland from this one.
            closeRequested.value = true

            thread.join(LOOP_JOIN_TIMEOUT_MILLIS)
            assertTrue(
                !thread.isAlive,
                "the event loop kept running after its only surface closed on a still-connected output",
            )
        } finally {
            pendingOutput?.let(Hyprctl::removeHeadlessOutput)
            // Closing would race the loop thread if it were still inside libwayland; safe only once it
            // has actually returned, which either a successful join or the mutation's immediate exit prove.
            if (loopThread?.isAlive == true) {
                fail(
                    "the event loop thread never returned, so its connection, its surfaces and the thread " +
                        "itself stay live: every later test in this worker runs against a poisoned session",
                )
            }
            shell?.close()
            display.close()
        }
    }

    private fun panelSpec(): SurfaceSpec =
        SurfaceSpec(PANEL_CONFIG) { Box(Modifier.fillMaxSize()) }

    private fun namedSpec(name: String): SurfaceSpec =
        SurfaceSpec(NAMED_CONFIG, OutputTarget.NamedOutput(name)) { Box(Modifier.fillMaxSize()) }

    /** Its content closes the surface itself once [closeRequested] flips, on the composition's own thread. */
    private fun namedClosableSpec(name: String, closeRequested: MutableState<Boolean>): SurfaceSpec =
        SurfaceSpec(NAMED_CONFIG, OutputTarget.NamedOutput(name)) {
            val surface = LocalKortexSurface.current
            val requested = closeRequested.value
            LaunchedEffect(requested) { if (requested) surface.close() }
            Box(Modifier.fillMaxSize())
        }

    /** Every monitor `hyprctl layers -j` reports [namespace] under. */
    private fun monitorsShowing(namespace: String): Set<String> = Hyprctl.layers()
        .filterValues { layers -> layers.levels.values.flatten().any { it.namespace == namespace } }
        .keys

    private fun panelNamespaces(): Set<String> =
        Hyprctl.namespaces().filterTo(mutableSetOf()) { it.startsWith("$PANEL_NAMESPACE-") }

    // A per-output surface's namespace carries the output's registry id as a suffix, like any other.
    private fun namedNamespace(): String? = Hyprctl.namespaces().firstOrNull { it.startsWith("$NAMED_NAMESPACE-") }

    // hyprctl only, never the shell: while a background loop thread runs, it alone may touch the display.
    private fun awaitNamedNamespace(present: Boolean, timeoutMillis: Long = PUMP_TIMEOUT_MILLIS): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if ((namedNamespace() != null) == present) return true
            Thread.sleep(HYPRCTL_POLL_MILLIS)
        }
        return (namedNamespace() != null) == present
    }

    // hyprctl's headless names are HEADLESS-<n>; the caller's own assertion catches it if that ever changes.
    private fun nextHeadlessName(name: String): String {
        val next = name.substringAfterLast('-').toInt() + 1
        return "${name.substringBeforeLast('-')}-$next"
    }

    /** Polls `hyprctl layers -j` until it reports [count] panel namespaces, since it lags the shell's own state. */
    private fun awaitPanelCount(count: Int): Set<String> {
        val deadline = System.nanoTime() + HYPRCTL_SETTLE_MILLIS * NANOS_PER_MILLI
        var namespaces = panelNamespaces()
        while (namespaces.size != count && System.nanoTime() < deadline) {
            Thread.sleep(HYPRCTL_POLL_MILLIS)
            namespaces = panelNamespaces()
        }
        return namespaces
    }

    private companion object {
        const val PANEL_NAMESPACE = "kortex-named-panel"
        const val NAMED_NAMESPACE = "kortex-named-target"
        const val PANEL_HEIGHT = 24
        const val OSD_SIZE = 96

        const val PUMP_TIMEOUT_MILLIS = 4000L
        const val HYPRCTL_SETTLE_MILLIS = 2000L
        const val HYPRCTL_POLL_MILLIS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
        const val LOOP_ALIVE_CHECK_MILLIS = 200L
        const val LOOP_JOIN_TIMEOUT_MILLIS = 3000L

        val PANEL_CONFIG = SurfaceConfig.panel(Edge.Top, PANEL_HEIGHT.dp).copy(namespace = PANEL_NAMESPACE)
        val NAMED_CONFIG = SurfaceConfig.osd(OSD_SIZE.dp, OSD_SIZE.dp).copy(namespace = NAMED_NAMESPACE)
    }
}
