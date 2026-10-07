plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: a caller hands in the SystemBus, and answers each request through its AuthConversation.
                api(project(":dbus"))
                api(project(":auth"))
            }
        }

        jvmTest {
            dependencies {
                implementation(project(":dbus-test"))
            }
        }
    }
}
