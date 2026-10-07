plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.kern.result)
                // api, not implementation: signals are a SharedFlow, so these types are in the surface.
                api(libs.kotlinx.coroutines.core)
                implementation(project(":socket"))
            }
        }

        jvmTest {
            dependencies {
                implementation(project(":dbus-test"))
            }
        }
    }
}
