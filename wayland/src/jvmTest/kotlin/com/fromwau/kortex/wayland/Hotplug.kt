package com.fromwau.kortex.wayland

import org.junit.jupiter.api.Tag

/**
 * Marks a test that adds or removes an output on the live desktop. Every such change makes Hyprland
 * re-send dmabuf feedback to every client, which crashes GTK 4.22.4 clients about one time in 256, so
 * the default build excludes tagged tests (see `settings.gradle.kts`). Run them with
 * `-Pkortex.hotplugTests=true`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Tag("hotplug")
annotation class Hotplug
