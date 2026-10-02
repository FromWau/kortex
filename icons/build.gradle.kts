plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

group = "com.fromwau.kortex"
version = libs.versions.kortexVersion.get()

// Plain JVM rather than multiplatform like its neighbours, deliberately: it reads the machine's icon
// directories and decodes with skia, and both of those are the JVM's. A commonMain here would compile
// today only because there is one target, which is the trap `:watch` documents.
kotlin {
    explicitApi()

    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // api on both sides: a caller hands in a provider's icon and gets back a Compose Painter, so both
    // vocabularies are in this module's surface.
    api(project(":tray"))
    api(project(":notification"))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    // The only decoder Compose still points at: loadImageBitmap and loadSvgPainter are both deprecated in
    // 1.12 in favour of decodeToImageBitmap and decodeToSvgPainter, which live here.
    implementation(libs.compose.components.resources)

    testImplementation(libs.kotlin.test)
    // Skia's native library, which a real application gets from compose.desktop.currentOs. Without it
    // every ColorSpace touch is a NoClassDefFoundError, which is what :compose's tests declare it for too.
    testImplementation(libs.skiko.awt.runtime.linux.x64)
}
