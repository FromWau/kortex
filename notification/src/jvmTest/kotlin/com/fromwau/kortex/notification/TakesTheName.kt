package com.fromwau.kortex.notification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Marks a test that takes `org.freedesktop.Notifications` from whatever daemon holds it.
 *
 * Only one connection on a bus may hold the name, so these cannot pass on a desktop that already shows
 * notifications, and the default build excludes tagged tests (see `settings.gradle.kts`). Leaving them in
 * would make `./gradlew check` fail on every ordinary session, which says nothing about kortex.
 *
 * Run them with the daemon stopped:
 * ```
 * systemctl --user stop dunst.service
 * ./gradlew :notification:jvmTest -Pkortex.notificationTests=true
 * systemctl --user start dunst.service
 * ```
 * Without that flag, a `--tests` filter naming only tagged tests fails with "No tests found for given
 * includes". A runner other than Gradle reports them disabled unless its JVM has
 * `-Dkortex.notificationTests=true`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Tag("notification-server")
@EnabledIfSystemProperty(named = "kortex.notificationTests", matches = "true")
annotation class TakesTheName
