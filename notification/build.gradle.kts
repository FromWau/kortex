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
                // api: a caller hands in the connection and reads the notifications as a StateFlow.
                api(project(":dbus"))
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
