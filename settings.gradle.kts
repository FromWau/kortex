@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // kern builds published to this machine only, ahead of the release repository: 0.4.3 and its
        // result-test are not released yet, so the build resolves only where they have been published locally.
        // TODO: remove this mavenLocal block once kern 0.4.3 and result-test are on maven.frommhund.xyz.
        mavenLocal {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

gradle.lifecycle.beforeProject {
    // Tests that change something the whole desktop shares, so each is opted into rather than run by
    // default. Each annotation carries the same tag and property as its entry here: Hotplug.kt under
    // wayland/src/jvmTest, and TakesTheName.kt under notification/src/jvmTest.
    val gatedByProperty = mapOf(
        "hotplug" to "kortex.hotplugTests",
        "notification-server" to "kortex.notificationTests",
    )
    val optedIn = gatedByProperty.filterValues { property ->
        providers.gradleProperty(property).getOrElse("false").toBoolean()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            (gatedByProperty.keys - optedIn.keys).forEach { tag -> excludeTags(tag) }
        }
        optedIn.values.forEach { property -> systemProperty(property, "true") }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

rootProject.name = "kortex"
include("theme")
include("compose")
include("wayland")
include("dbus")
include("watch")
include("icons")
include("tray")
include("notification")
include("hyprland")
include("shell")
include("bar")
