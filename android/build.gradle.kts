plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// AV-022's dependency directions. :core is pure Kotlin/JVM, so no session rule can
// call a platform API; each adapter sees only :core; :app is the composition root.
val allowedProjectDependencies = mapOf(
    ":core" to emptySet(),
    ":ankidroid" to setOf(":core"),
    ":speech" to setOf(":core"),
    ":provider" to setOf(":core"),
    ":app" to setOf(":core", ":ankidroid", ":speech", ":provider"),
)
// AV-020 (#17): :provider is the app's only network route, and no module asks for phone state.
val networkPermission = "android.permission.INTERNET"
val forbiddenPermission = "android.permission.READ_PHONE_STATE"
val networkModule = ":provider"
val androidPlugins = listOf("com.android.application", "com.android.library")
val androidGroups = listOf("android", "androidx", "com.android", "com.google.android")
// The :core fakes may reach debug builds and tests, never a release build.
val fixtureConfigurationPrefixes = listOf("debug", "test", "androidTest")

evaluationDependsOnChildren()

val boundaryViolations: List<String> = buildList {
    val modules = subprojects.associateBy { it.path }
    if (modules.keys != allowedProjectDependencies.keys) {
        add("modules are ${modules.keys.sorted()}, AV-022 defines ${allowedProjectDependencies.keys.sorted()}")
    }
    for ((path, module) in modules) {
        val declared = module.configurations.flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>()
                .filter { it.path != path }
                .map { configuration.name to it }
        }
        val targets = declared.map { (_, dependency) -> dependency.path }.toSet()
        val allowed = allowedProjectDependencies[path].orEmpty()
        if (targets != allowed) add("$path depends on ${targets.sorted()}; AV-022 allows ${allowed.sorted()}")
        for ((configuration, dependency) in declared) {
            val featureVariant = dependency.capabilitySelectors.isNotEmpty()
            if (featureVariant && fixtureConfigurationPrefixes.none { configuration.startsWith(it) }) {
                add("$path puts a ${dependency.path} feature variant on '$configuration', which a release build can see")
            }
        }
    }
    for ((path, module) in modules) {
        val manifest = module.file("src/main/AndroidManifest.xml")
        val declared = if (manifest.isFile) manifest.readText() else ""
        if (declared.contains(networkPermission) != (path == networkModule)) {
            add(
                if (path == networkModule) "$networkModule must declare $networkPermission; it is the app's only network route"
                else "$path declares $networkPermission; only $networkModule may",
            )
        }
        if (declared.contains(forbiddenPermission)) add("$path declares $forbiddenPermission, which AV-020 forbids")
    }
    modules[":core"]?.let { core ->
        androidPlugins.filter { core.pluginManager.hasPlugin(it) }
            .forEach { add(":core applies $it; it must stay pure Kotlin/JVM") }
        core.configurations.flatMap { it.dependencies.withType<ExternalModuleDependency>() }
            .filter { dependency -> androidGroups.any { dependency.group == it || dependency.group.orEmpty().startsWith("$it.") } }
            .forEach { add(":core declares Android dependency ${it.group}:${it.name}") }
    }
}

val checkModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Fails if the module graph breaks AV-022's dependency directions."
    val violations = boundaryViolations
    doLast {
        if (violations.isNotEmpty()) {
            throw GradleException("Module boundary violations:\n" + violations.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("Module boundaries follow AV-022.")
    }
}

tasks.named("check") { dependsOn(checkModuleBoundaries) }
