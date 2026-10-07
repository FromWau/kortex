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
                // api for both: a caller holds the Path, reads the Flow and matches on the FileError inside
                // a WatchError, so all three are in this module's surface.
                api(libs.kern.dirs)
                api(libs.kotlinx.coroutines.core)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kern.result.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
