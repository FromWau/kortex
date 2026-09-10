package com.fromwau.kortex.wayland

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Marks a test that adds or removes an output on the live desktop. Every such change makes Hyprland
 * re-send dmabuf feedback to every client, which crashes GTK 4.22.4 clients about one time in 256, so
 * the default build excludes tagged tests (see `settings.gradle.kts`).
 *
 * Run them with `./gradlew :wayland:jvmTest -Pkortex.hotplugTests=true`. Without that flag, a `--tests`
 * filter naming only tagged tests fails with "No tests found for given includes". A runner other than
 * Gradle reports them disabled unless its JVM has `-Dkortex.hotplugTests=true`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Tag("hotplug")
@EnabledIfSystemProperty(named = "kortex.hotplugTests", matches = "true")
annotation class Hotplug
