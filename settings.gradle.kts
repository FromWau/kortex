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
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

gradle.lifecycle.beforeProject {
    // Hotplug.kt, in wayland/src/jvmTest/kotlin/com/fromwau/kortex/wayland, must use the same two names.
    val hotplugTag = "hotplug"
    val hotplugProperty = "kortex.hotplugTests"
    val hotplugTestsOptedIn = providers
        .gradleProperty(hotplugProperty)
        .getOrElse("false")
        .toBoolean()

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            if (!hotplugTestsOptedIn) excludeTags(hotplugTag)
        }
        if (hotplugTestsOptedIn) systemProperty(hotplugProperty, "true")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

rootProject.name = "kortex"
include("compose")
include("wayland")
include("dbus")
include("tray")
include("notification")
include("bar")
