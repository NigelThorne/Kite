import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Gradle plugin that processes Kite script @file:Import annotations
 * to help IDEs understand script dependencies.
 *
 * This scans .kts files for @file:Import("path/to/file.kts") annotations
 * and generates synthetic imports to help kotlin-lsp understand the relationships.
 */
class KiteScriptImportProcessorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kiteScripts", KiteScriptExtension::class.java)

        val processTask =
            project.tasks.register("processKiteScriptImports", ProcessKiteScriptImportsTask::class.java) { task ->
                task.scriptDirectory.set(extension.scriptDirectory)
                task.outputDirectory.set(project.layout.buildDirectory.dir("generated/kite-script-imports"))
            }

        // Hook into Kotlin compilation
        project.tasks.matching { it.name.contains("compileKotlin", ignoreCase = true) }.configureEach {
            it.dependsOn(processTask)
        }

        // Add generated sources to source sets
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java).sourceSets.named("main") { sourceSet ->
                sourceSet.java.srcDir(project.layout.buildDirectory.dir("generated/kite-script-imports"))
            }
        }
    }
}

abstract class KiteScriptExtension {
    abstract val scriptDirectory: DirectoryProperty
}

abstract class ProcessKiteScriptImportsTask : DefaultTask() {
    @get:InputDirectory
    abstract val scriptDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private val importRegex = """@file:Import\s*\(\s*"([^"]+)"\s*\)""".toRegex()

    @TaskAction
    fun process() {
        val scriptDir = scriptDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile

        // Clean output directory
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        // Scan all .kts files
        scriptDir.walkTopDown()
            .filter { it.extension == "kts" }
            .forEach { scriptFile ->
                processScriptFile(scriptFile, scriptDir, outputDir)
            }

        logger.lifecycle("Processed Kite script imports: ${scriptDir.absolutePath}")
    }

    private fun processScriptFile(scriptFile: File, baseDir: File, outputDir: File) {
        val content = scriptFile.readText()
        val imports = extractImports(content)

        if (imports.isEmpty()) return

        // Generate a synthetic Kotlin file that declares the dependencies
        val relativePath = scriptFile.relativeTo(baseDir).path
        val syntheticFileName = scriptFile.nameWithoutExtension + "_imports.kt"
        val syntheticFile = File(outputDir, syntheticFileName)

        syntheticFile.writeText(generateSyntheticFile(scriptFile, imports, baseDir))

        logger.info("Generated import metadata for ${scriptFile.name}: ${imports.size} imports")
    }

    private fun extractImports(content: String): List<String> {
        return importRegex.findAll(content)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun generateSyntheticFile(scriptFile: File, imports: List<String>, baseDir: File): String {
        val packageName = "kite.script.imports"
        val className = scriptFile.nameWithoutExtension.replace("-", "_").replace(".", "_")

        return buildString {
            appendLine("// Generated file - do not edit")
            appendLine("// This file helps IDEs understand @file:Import dependencies in Kite scripts")
            appendLine("package $packageName")
            appendLine()
            appendLine("/**")
            appendLine(" * Metadata for script: ${scriptFile.name}")
            appendLine(" * Imports: ${imports.joinToString(", ")}")
            appendLine(" */")
            appendLine("object ${className}_Imports {")
            appendLine("    val imports = listOf(")
            imports.forEach { import ->
                appendLine("        \"$import\",")
            }
            appendLine("    )")
            appendLine("}")
            appendLine()
            appendLine("// Note: This is a metadata file only.")
            appendLine("// The actual script imports are handled by Kite's runtime.")
        }
    }
}
