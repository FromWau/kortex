plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller hands in the SystemBus and reads the profiles as a StateFlow.
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
