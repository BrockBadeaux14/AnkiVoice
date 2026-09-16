import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the AV-007 contracts and, later, the session rules. No Android
// plugin or dependency may be added here (see checkModuleBoundaries).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

val javaRelease = libs.versions.java.get()

java {
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaRelease)
        // Compile against the Java 17 class library, not the JDK running Gradle.
        freeCompilerArgs.add("-Xjdk-release=$javaRelease")
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease.toInt()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // AV-015's fixture test reads the VoiceQA examples from the repository, not a copy.
    val voiceqaNoteType = layout.projectDirectory.file("../../fixtures/voiceqa/note-type.json")
    inputs.file(voiceqaNoteType).withPropertyName("voiceqaNoteType").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("ankivoice.voiceqaNoteType", voiceqaNoteType.asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
