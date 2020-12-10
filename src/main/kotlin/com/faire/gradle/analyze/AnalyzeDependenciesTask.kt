package com.faire.gradle.analyze

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

open class DependencyAnalysisException(message: String) : GradleException(message) {}

open class AnalyzeDependenciesTask : DefaultTask() {
  @InputFiles
  var mainClassesDirs: FileCollection = project.files()

  @InputFiles
  var testClassesDirs: FileCollection = project.files()

  @Input
  var justWarn: Boolean = false

  @Input
  var includedPackageFilters: List<String> = listOf()

  private fun applyAnalysisResultToBuffer(
      outputBuffer: StringBuffer,
      problemArtifacts: Set<ResolvedArtifact>, problemLabel: String) {
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
        outputBuffer.append(" - ${artifact.moduleVersion}${classifier}@${artifact.extension}\n")
      }
    }
  }

  @TaskAction
  fun analyzeDependencies() {
    val analysis = ProjectDependencyResolver(project, mainClassesDirs, testClassesDirs).analyzeDependencies()
    val buffer = StringBuffer()

    applyAnalysisResultToBuffer(buffer, analysis.mainUsedUndeclaredArtifacts, "mainUsedUndeclaredArtifacts")
    applyAnalysisResultToBuffer(buffer, analysis.testUsedUndeclaredArtifacts, "testUsedUndeclaredArtifacts")
    applyAnalysisResultToBuffer(
        buffer,
        analysis.mainUnusedDeclaredButUsedByTest,
        "mainUnusedDeclaredButUsedByTest"
    )
    applyAnalysisResultToBuffer(buffer, analysis.mainUnusedDeclaredArtifacts, "mainUnusedDeclaredArtifacts")
    applyAnalysisResultToBuffer(buffer, analysis.testUnusedDeclaredArtifacts, "testUnusedDeclaredArtifacts")
    applyAnalysisResultToBuffer(buffer, analysis.testUnnecessaryDeclarations, "testUnnecessaryDeclarations")
    applyAnalysisResultToBuffer(
        buffer,
        analysis.mainUnnecessaryPermitUnusedDeclaredArtifacts,
        "mainUnnecessaryPermitUnusedDeclaredArtifacts"
    )
    applyAnalysisResultToBuffer(
        buffer,
        analysis.testUnnecessaryPermitUnusedDeclaredArtifacts,
        "testUnnecessaryPermitUnusedDeclaredArtifacts"
    )

    if (!buffer.isEmpty()) {
      val message = "Dependency analysis found issues.\n$buffer"
      if (justWarn) {
        logger.warn(message)
      } else {
        throw DependencyAnalysisException(message)
      }
    }
  }
}
