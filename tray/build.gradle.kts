plugins {
    id("kortex-probe")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller holds the connection and hands it in, and reads Tray.items as a StateFlow.
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
