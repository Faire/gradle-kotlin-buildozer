package com.faire.gradle.analyze

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

open class AnalyzeDependenciesPluginExtension {
  var justWarn: Boolean = false
  var includedPackageFilters: List<String> = listOf()
}

class AnalyzeDependenciesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create<AnalyzeDependenciesPluginExtension>("analyzeDependencies")

    // If we have a directory that doesn't have a build-file, the analyze task is going to fail.
    // Sometimes gradle thinks there is a project at a location with no build file.
    // The situation I had was as follows
    //    foo (no build file here)
    //    foo/bar1 (has build file)
    //    foo/bar2 (has build file)
    //
    // Running ./gradlew analyzeDependencies would fail at :foo:analyzeDependencies
    if (!project.buildFile.exists()) {
      return
    }

    project.configurations.create("permitUnusedDeclared")
    project.configurations.create("permitTestUnusedDeclared")

    val javaPlugin = project.convention.getPlugin(JavaPluginConvention::class.java)
    val mainSourceSet = javaPlugin.sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
    val testSourceSet = javaPlugin.sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME).get()
    val classesOutput = mainSourceSet.output
    val testClassesOutput = testSourceSet.output

    project.tasks.register<AnalyzeDependenciesTask>("analyzeDependencies") {
      group = "Analyze"
      description = "Check dependencies for unnecessary or missing entries."
      dependsOn(project.tasks.named("compileKotlin").get())
      dependsOn(project.tasks.named("compileTestKotlin").get())
      mainClassesDirs = classesOutput.classesDirs
      testClassesDirs = testClassesOutput.classesDirs
      justWarn = extension.justWarn
      includedPackageFilters = extension.includedPackageFilters
    }
  }
}

