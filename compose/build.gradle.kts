plugins {
    id("kortex-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.compose.runtime)
                api(libs.compose.ui)
                api(libs.compose.foundation)
                api(libs.kern.result)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.skiko.awt.runtime.linux.x64)
            }
        }
    }
}
