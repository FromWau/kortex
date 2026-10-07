plugins {
    id("kortex-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // api: AuthError carries the SocketError a backend's socket failed with, and state is a StateFlow.
                api(project(":socket"))
            }
        }
    }
}
