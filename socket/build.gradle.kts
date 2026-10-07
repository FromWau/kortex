plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller matches on the Result every read and write answers, and collects lines().
                api(libs.kern.result)
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
