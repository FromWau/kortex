plugins {
    id("kortex-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller reads the flows and matches on the Result inside them.
                api(libs.kern.result)
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(project(":socket"))
            }
        }
    }
}
