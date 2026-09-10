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
    // See Hotplug.kt's KDoc, in wayland/src/jvmTest/kotlin/com/fromwau/kortex/wayland.
    val hotplugTag = "hotplug"

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            if (!providers.gradleProperty("kortex.hotplugTests").getOrElse("false").toBoolean()) {
                excludeTags(hotplugTag)
            }
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

rootProject.name = "kortex"
include("compose")
include("wayland")
include("bar")
