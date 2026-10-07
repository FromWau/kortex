plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller hands in the SessionBus and reads the players as a StateFlow.
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
