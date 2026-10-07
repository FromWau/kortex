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
                // api: a caller hands in the SystemBus and reads the profiles as a StateFlow.
                api(project(":dbus"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kern.result.test)
                implementation(project(":dbus-test"))
            }
        }
    }
}
