plugins {
    id("kortex-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":watch"))
                api(libs.compose.runtime)
                api(libs.compose.ui)
                // Nothing here imports foundation. It is declared to pin it to the Compose plugin's own
                // version, which material3's older transitive otherwise wins: 1.9.1 against 1.12.0, which
                // the compose compatibility check then warns about.
                api(libs.compose.foundation)
                api(libs.material3)
                api(libs.kern.result)
                api(libs.kotlinx.coroutines.core)
                api(libs.kern.dirs)
                api(libs.kotlinx.serialization.json)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.skiko.awt.runtime.linux.x64)
            }
        }
    }
}
