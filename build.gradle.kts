import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class VerifyDependencyPins : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val inlinePluginVersion = Regex("""(?m)^\s*id\([^)]*\)\s+version\s+""")
        val floatingVersion = Regex("""(?i)(?:latest[._-]|\+|\[|\])""")
        val catalog = sources.files.first { it.name == "libs.versions.toml" }.readText()
        sources.files.forEach { source ->
            val text = source.readText()
            if (source.name == "settings.gradle.kts") {
                check(text.contains("id(\"org.gradle.toolchains.foojay-resolver-convention\") version \"1.0.0\"")) {
                    "The settings plugin must stay pinned to the catalogued Foojay version."
                }
                check(catalog.contains("foojayResolver = \"1.0.0\"")) {
                    "Catalog and settings Foojay versions must match."
                }
            } else {
                check(!inlinePluginVersion.containsMatchIn(text)) {
                    "Inline plugin version is prohibited: ${source.name}"
                }
            }
            if (source.name == "libs.versions.toml") {
                text.lineSequence().filter { " = " in it }.forEach { line ->
                    check(!floatingVersion.containsMatchIn(line.substringAfter('='))) {
                        "Floating catalog version is prohibited: $line"
                    }
                }
            }
        }
    }
}

abstract class VerifySensitiveDataLeakage : DefaultTask() {
    @get:InputFiles
    abstract val productionSources: ConfigurableFileCollection

    @get:InputFiles
    abstract val buildLogicSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val rawLogging = Regex("""\b(?:android\.util\.)?Log\.[A-Za-z]+\s*\(|\bprintln\s*\(""")
        val crashReporting = Regex("""(?i)crash(?:lytics)|firebase\.crash|sentr(?:y)|bugsn(?:ag)""")
        productionSources.files.forEach { source ->
            val text = source.readText()
            check(!rawLogging.containsMatchIn(text)) { "Raw logging is prohibited: ${source.name}" }
            check(!crashReporting.containsMatchIn(text)) {
                "Crash reporting integration requires explicit security review: ${source.name}"
            }
        }
        buildLogicSources.files.forEach { source ->
            check(!crashReporting.containsMatchIn(source.readText())) {
                "Crash reporting dependency/configuration requires explicit security review: ${source.name}"
            }
        }
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val buildLogicSourceFiles = fileTree(rootDir) {
    include("**/*.gradle.kts", "gradle/libs.versions.toml")
    exclude("**/.gradle/**", "**/build/**")
}
val productionSourceFiles = fileTree(rootDir) {
    include("**/src/main/**/*.kt", "**/src/main/**/*.java")
    exclude("**/build/**")
}

tasks.register<VerifyDependencyPins>("verifyDependencyPins") {
    group = "verification"
    description = "Rejects inline and floating dependency/plugin versions outside the version catalog."
    sources.from(buildLogicSourceFiles)
}

tasks.register<VerifySensitiveDataLeakage>("verifySensitiveDataLeakage") {
    group = "verification"
    description = "Rejects raw logging and crash-reporting integrations from production sources."
    productionSources.from(productionSourceFiles)
    buildLogicSources.from(buildLogicSourceFiles)
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyDependencyPins"))
        dependsOn(rootProject.tasks.named("verifySensitiveDataLeakage"))
    }
}
