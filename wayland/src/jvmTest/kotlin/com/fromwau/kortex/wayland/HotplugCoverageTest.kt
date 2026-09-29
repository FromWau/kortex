package com.fromwau.kortex.wayland

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test as JUnitTest

/**
 * Names every test this desktop does not run, because the report cannot.
 *
 * A `@Hotplug` test is excluded by tag (`settings.gradle.kts`), and an excluded test leaves nothing behind:
 * no `<skipped/>`, no result file. `TEST-...SurfaceScaleTest.xml` records `tests="1"` for a two-`@Test`
 * file, and three whole classes produce no XML at all, so the run reads as complete while a fifth of the
 * output coverage never executed. That is the worst shape a gap can take: missing coverage reporting as
 * covered.
 *
 * Adding or removing a `@Hotplug` therefore has to be a deliberate act, which is what the list below makes
 * it. Its size is the honest headline: that many tests were not run here.
 */
class HotplugCoverageTest {
    @Test
    fun `the tests this desktop cannot run are exactly the ones named here`() {
        val excluded = hotplugTests()

        assertTrue(excluded.isNotEmpty(), "no @Hotplug test was found at all, so this test is watching nothing")
        assertEquals(
            EXCLUDED, excluded,
            "the set of tests excluded from every default run has changed; the report will not say so",
        )
    }

    private companion object {
        /**
         * Every `@Hotplug` test, as `ClassName.test name`.
         *
         * They change an output on the live desktop, which makes Hyprland re-send dmabuf feedback to every
         * client on it, and the installed GTK crashes on that roughly one run in 256. So they are excluded
         * here rather than quarantined, and this list is the only place their absence is written down.
         */
        val EXCLUDED = setOf(
            "KortexShellTest.one surface per monitor, created and destroyed as monitors come and go",
            "MultiSurfaceTest.a hotplugged output grows the per-monitor panel and nothing else",
            "NamedOutputTest.a surface shown on a chosen monitor appears under that output and no other",
            "NamedOutputTest.removing a surface's monitor drops it and leaves the other panels standing",
            "OutputHotplugTest.a hotplugged output updates globals and fires add-remove notifications",
            "OutputReleaseWireTest.every bound output is released, the hotplugged one on removal and the " +
                "rest on shell close",
            "SurfaceScaleTest.a second bar on a differently scaled output renders at that output's scale",
        )

        /** Where the compiled test classes for this module are, which is where this class itself came from. */
        fun testClassRoot(): File =
            File(HotplugCoverageTest::class.java.protectionDomain.codeSource.location.toURI())

        /**
         * Every test method a `@Hotplug` keeps out of a default run, whether the annotation is on the method
         * or on the class around it.
         *
         * Loaded without initialising: a test class's own companion can open a connection, and nothing here
         * needs one to read an annotation.
         */
        fun hotplugTests(): Set<String> {
            val root = testClassRoot()
            assertTrue(root.isDirectory, "$root is not the directory the compiled test classes are in")

            return root.walkTopDown()
                .filter { it.isFile && it.extension == "class" && '$' !in it.name }
                .mapNotNull { file ->
                    val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                    runCatching { Class.forName(name, false, HotplugCoverageTest::class.java.classLoader) }.getOrNull()
                }
                .flatMap { type ->
                    val wholeClass = type.isAnnotationPresent(Hotplug::class.java)
                    type.declaredMethods
                        .filter { it.isAnnotationPresent(JUnitTest::class.java) }
                        .filter { wholeClass || it.isAnnotationPresent(Hotplug::class.java) }
                        .map { "${type.simpleName}.${it.name}" }
                }
                .toSet()
        }
    }
}
