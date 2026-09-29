plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
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
                api(project(":compose"))
                api(libs.kern.result)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.skiko.awt.runtime.linux.x64)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// A probe under jvmTest that waits for a person rather than driving itself; no test task can run one, since
// a suite that blocks on a hand never finishes. The test runtime classpath alone, not the main one with the
// test one appended: that mixes two kotlinx-serialization versions and the generated serializers break.
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Runs a jvmTest main by name: -Pprobe=com.fromwau.kortex.wayland.LiveDragProbeKt"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass = providers.gradleProperty("probe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Stdin stays connected so a probe can wait on a keypress rather than only on a clock.
    standardInput = System.`in`
}
