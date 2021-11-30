package com.faire.gradle.analyze

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

open class DependencyAnalysisException(message: String) : GradleException(message)

@CacheableTask
open class AnalyzeDependenciesTask : DefaultTask() {
  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  var mainClassesDirs: FileCollection = project.files()

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  var testClassesDirs: FileCollection = project.files()

  // Using the buildFile as an input so that if you change any dependencies we invalidate analysis cache.
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  var buildFile = project.buildFile

  @Input
  var justWarn: Boolean = false

  @Input
  var includedPackageFilters: List<String> = listOf()

  @OutputFile
  var outFile = project.buildDir.resolve("buildozerOutput.txt")

  private fun applyAnalysisResultToBuffer(
    outputBuffer: StringBuffer,
    problemArtifacts: Set<ResolvedArtifact>,
    problemLabel: String
  ) {
    val filteredProblems = if (includedPackageFilters.isEmpty()) {
      problemArtifacts
    } else {
      problemArtifacts.filter { artifact ->
        includedPackageFilters.any {
          artifact.moduleVersion.toString().contains(it)
        }
      }
    }

    if (!filteredProblems.isEmpty()) {
      outputBuffer.append("$problemLabel: \n")
      filteredProblems.forEach { artifact ->
        val classifier = artifact.classifier ?: ""
        outputBuffer.append(" - ${artifact.moduleVersion}$classifier@${artifact.extension}\n")
      }
    }
  }

  @TaskAction
  fun analyzeDependencies() {
    val analysis = ProjectDependencyResolver(project, mainClassesDirs, testClassesDirs).analyzeDependencies()
    val buffer = StringBuffer()

    applyAnalysisResultToBuffer(
      buffer,
      analysis.mainUsedUndeclaredArtifacts,
      MAIN_USED_UNDECLARED_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.testUsedUndeclaredArtifacts,
      TEST_USED_UNDECLARED_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.mainUnusedDeclaredButUsedByTest,
      MAIN_UNUSED_DECLARED_BUT_USED_BY_TEST_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.mainUnusedDeclaredArtifacts,
      MAIN_UNUSED_DECLARED_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.testUnusedDeclaredArtifacts,
      TEST_UNUSED_DECLARED_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.testUnnecessaryDeclarations,
      TEST_UNNECESSARY_DECLARATIONS_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.mainUnnecessaryPermitUnusedDeclaredArtifacts,
      MAIN_UNNECESSARY_PERMIT_UNUSED_DECLARED_MSG
    )
    applyAnalysisResultToBuffer(
      buffer,
      analysis.testUnnecessaryPermitUnusedDeclaredArtifacts,
      TEST_UNNECESSARY_PERMIT_UNUSED_DECLARED_MSG
    )

    val output = buffer.toString()
    outFile.writeText(output)

    if (!buffer.isEmpty()) {
      val message = "Dependency analysis found issues.\n$buffer"
      if (justWarn) {
        logger.warn(message)
      } else {
        throw DependencyAnalysisException(message)
      }
    }
  }

  companion object {
    const val MAIN_USED_UNDECLARED_MSG = "Main dependencies used, but not declared in gradle"
    const val TEST_USED_UNDECLARED_MSG = "Test dependencies used, but not declared in gradle"
    const val MAIN_UNUSED_DECLARED_BUT_USED_BY_TEST_MSG = "Main dependencies not used, but are used by test" +
        " -- e.g. change implementation() to testImplementation() in build.gradle.kts"
    const val MAIN_UNUSED_DECLARED_MSG = "Main dependencies not used, but declared in gradle" +
        " -- remove implementation()/api() references in build.gradle.kts"
    const val TEST_UNUSED_DECLARED_MSG = "Test dependencies not used, but declared in gradle" +
        " -- remove testImplementation()/testApi() references in build.gradle.kts"
    const val TEST_UNNECESSARY_DECLARATIONS_MSG = "Test dependencies already declared by main" +
        " -- remove testImplementation()/testApi() references in build.gradle.kts"
    const val MAIN_UNNECESSARY_PERMIT_UNUSED_DECLARED_MSG = "Main dependency is listed as permitUnusedDeclared()" +
        " but does not need to be -- remove its permitUnusedDeclared() configuration in build.gradle.kts"
    const val TEST_UNNECESSARY_PERMIT_UNUSED_DECLARED_MSG = "Test dependency is listed as permitTestUnusedDeclared()" +
        " but does not need to be -- remove its permitTestUnusedDeclared() configuration in build.gradle.kts"
  }
}
