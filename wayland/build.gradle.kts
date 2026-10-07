plugins {
    id("kortex-probe")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":compose"))
                api(libs.kern.result)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.skiko.awt.runtime.linux.x64)
                implementation(libs.kotlinx.serialization.json)
                // Tests only, and for one probe: a transfer key arrives on the Wayland wire and means
                // nothing until a D-Bus call turns it into paths, so proving the two halves compose needs
                // both. The module itself must not depend on this, which is what keeps "no socket" true of
                // the toolkit, so nothing under commonMain may reach for it.
                implementation(project(":dbus"))
            }
        }
    }
}
