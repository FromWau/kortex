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
    implementation(project(":polkit"))
    implementation(libs.kern.result)
    implementation(libs.compose.material3)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kern.result.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.fromwau.kortex.polkitagent.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "kortex-polkit-agent"
            packageVersion = libs.versions.kortexVersion.get()
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
