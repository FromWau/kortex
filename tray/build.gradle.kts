plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "com.fromwau.kortex"
version = libs.versions.kortexVersion.get()

kotlin {
    explicitApi()

    jvmToolchain(libs.versions.jdk.get().toInt())

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                // api: a caller holds the connection and hands it in, and reads Tray.items as a StateFlow.
                api(project(":dbus"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// A probe under jvmTest that waits for a person rather than driving itself; no test task can run one,
// since a suite that blocks on a hand never finishes.
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Runs a jvmTest main by name: -Pprobe=com.fromwau.kortex.tray.LiveTrayProbeKt"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass = providers.gradleProperty("probe")
    // Stdin stays connected so a probe can wait on a keypress rather than only on a clock.
    standardInput = System.`in`
}
