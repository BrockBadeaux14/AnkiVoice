plugins {
    id("com.android.application")
}

android {
    namespace = "org.ankivoice.av005"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ankivoice.av005"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "AV005-1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * The spoken turns are derived from the merged AV-002 fixtures rather than restated
 * here, so the probe and the fixtures cannot drift apart.
 */
val generateTurns by tasks.registering(Exec::class) {
    val script = rootProject.file("build_corpus.py")
    val output = file("src/main/assets/av005-turns.json")
    inputs.file(rootProject.file("../../fixtures/voiceqa/note-type.json"))
    inputs.file(script)
    outputs.file(output)
    commandLine("python3", script.absolutePath, output.absolutePath)
}

tasks.named("preBuild") { dependsOn(generateTurns) }
