import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(project(":wayland"))
    implementation(project(":theme"))
    implementation(libs.compose.material3)
    implementation(compose.desktop.currentOs)

    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "com.fromwau.kortex.bar.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "kortex-bar"
            packageVersion = libs.versions.kortexVersion.get()
        }
    }
}
