plugins {
    id("kortex-library")
}

kotlin {
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
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
