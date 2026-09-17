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
// AV-043: the paid route's daily cap for a recorded pass (USD; 0 measures the free route
// alone), and the bounded model spike, which runs one paid candidate on the tuning 20.
val av017DailyCapUsd = providers.gradleProperty("av017.dailyCapUsd")
val av043Spike = providers.gradleProperty("av043.spike")
val av043SpikeMaxPromptUsd = providers.gradleProperty("av043.spikeMaxPromptUsd")
val av043SpikeMaxCompletionUsd = providers.gradleProperty("av043.spikeMaxCompletionUsd")
// AV-043's regression test replays the recorded evidence that exposed the guard defect.
val av017Transcript = layout.projectDirectory.file("../../docs/testing/av017/evidence/ai-20260916/transcript.jsonl")
val av006GradeB = layout.projectDirectory.file("../../docs/testing/av006/evidence/openrouter-grade-b.json")
val av006GradeBPass2 = layout.projectDirectory.file("../../docs/testing/av006/evidence/openrouter-grade-b-pass2.json")
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
    inputs.property("av017DailyCapUsd", av017DailyCapUsd.getOrElse(""))
    inputs.property("av043Spike", av043Spike.getOrElse(""))
    inputs.property("av043SpikeMaxPromptUsd", av043SpikeMaxPromptUsd.getOrElse(""))
    inputs.property("av043SpikeMaxCompletionUsd", av043SpikeMaxCompletionUsd.getOrElse(""))
    inputs.file(av017Transcript).withPropertyName("av017Transcript").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(av006GradeB).withPropertyName("av006GradeB").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(av006GradeBPass2).withPropertyName("av006GradeBPass2").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("ankivoice.av017.corpus", av017Corpus.asFile.absolutePath)
    systemProperty("ankivoice.av017.mode", av017Mode)
    systemProperty("ankivoice.av017.out", av017Out)
    systemProperty("ankivoice.av017.split", av017Split)
    systemProperty("ankivoice.av043.av017Transcript", av017Transcript.asFile.absolutePath)
    systemProperty("ankivoice.av043.av006GradeB", av006GradeB.asFile.absolutePath)
    systemProperty("ankivoice.av043.av006GradeBPass2", av006GradeBPass2.asFile.absolutePath)
    av017DailyLimit.orNull?.let { systemProperty("ankivoice.av017.dailyLimit", it) }
    av017DailyCapUsd.orNull?.let { systemProperty("ankivoice.av017.dailyCapUsd", it) }
    av043Spike.orNull?.let { systemProperty("ankivoice.av043.spike", it) }
    av043SpikeMaxPromptUsd.orNull?.let { systemProperty("ankivoice.av043.spikeMaxPromptUsd", it) }
    av043SpikeMaxCompletionUsd.orNull?.let { systemProperty("ankivoice.av043.spikeMaxCompletionUsd", it) }
    if (av017Mode == "record") {
        openRouterKey.orNull?.let { environment("OPENROUTER_API_KEY", it) }
        // A live pass must not be served from an earlier run's output.
        outputs.upToDateWhen { false }
    }
}
