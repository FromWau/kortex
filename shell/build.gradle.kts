plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller matches on the Result shell answers with.
                api(libs.kern.result)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
