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
                api(libs.kern.result)
                // api, not implementation: signals are a SharedFlow, so these types are in the surface.
                api(libs.kotlinx.coroutines.core)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
