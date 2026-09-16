plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.ankivoice.provider"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// AV-017's evaluation harness. `rule-only` is the default, so an ordinary build and CI
// run the corpus through the shipped graders with no credential and no socket. The live
// `record` pass and the offline `replay` scoring run are opt-in; see
// docs/testing/av017/runbook.md.
val av017Corpus = layout.projectDirectory.file("../../fixtures/grading/av017-corpus.json")
val av017Mode = providers.gradleProperty("av017.mode").getOrElse("rule-only")
val av017Out = providers.gradleProperty("av017.out")
    .getOrElse(layout.buildDirectory.dir("av017").get().asFile.absolutePath)
val av017DailyLimit = providers.gradleProperty("av017.dailyLimit")
val av017Split = providers.gradleProperty("av017.split").getOrElse("all")
// Read from the shell that starts the build, never from a file: AV-020 forbids storing
// the key anywhere, and only the `record` pass reads it.
val openRouterKey = providers.environmentVariable("OPENROUTER_API_KEY")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.file(av017Corpus).withPropertyName("av017Corpus").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("av017Mode", av017Mode)
    inputs.property("av017Out", av017Out)
    inputs.property("av017DailyLimit", av017DailyLimit.getOrElse(""))
    inputs.property("av017Split", av017Split)
    systemProperty("ankivoice.av017.corpus", av017Corpus.asFile.absolutePath)
    systemProperty("ankivoice.av017.mode", av017Mode)
    systemProperty("ankivoice.av017.out", av017Out)
    systemProperty("ankivoice.av017.split", av017Split)
    av017DailyLimit.orNull?.let { systemProperty("ankivoice.av017.dailyLimit", it) }
    if (av017Mode == "record") {
        openRouterKey.orNull?.let { environment("OPENROUTER_API_KEY", it) }
        // A live pass must not be served from an earlier run's output.
        outputs.upToDateWhen { false }
    }
}
