plugins {
    id("kortex-probe")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller hands in the connection and reads the notifications as a StateFlow.
                api(project(":dbus"))
            }
        }

        jvmTest {
            dependencies {
                implementation(project(":dbus-test"))
            }
        }
    }
}
