plugins {
    id("kortex-library")
}

// A probe under jvmTest waits for a person rather than driving itself, so no test task can run one: a suite
// that blocks on a hand never finishes. The test runtime classpath alone, not the main one with the test one
// appended: that mixes two kotlinx-serialization versions and the generated serializers break.
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Runs a jvmTest main by name: -Pprobe=<its fully qualified class>"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass = providers.gradleProperty("probe")
    // For the probes that drive Wayland, whose bindings are foreign calls.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Stdin stays connected so a probe can wait on a keypress rather than only on a clock.
    standardInput = System.`in`
}
