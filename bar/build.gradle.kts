import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":wayland"))
    implementation(libs.compose.material3)
    implementation(compose.desktop.currentOs)
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
