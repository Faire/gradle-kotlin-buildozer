package com.faire.gradle.analyze

import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File
import java.net.URLClassLoader

open class ProjectDependencyAnalysis constructor(
    val mainUsedUndeclaredArtifacts: Set<ResolvedArtifact>,
    val testUsedUndeclaredArtifacts: Set<ResolvedArtifact>,
    val mainUnusedDeclaredButUsedByTest: Set<ResolvedArtifact>,
    val mainUnusedDeclaredArtifacts: Set<ResolvedArtifact>,
    val testUnusedDeclaredArtifacts: Set<ResolvedArtifact>,
    val testUnnecessaryDeclarations: Set<ResolvedArtifact>,
    val mainUnnecessaryPermitUnusedDeclaredArtifacts: Set<ResolvedArtifact>,
    val testUnnecessaryPermitUnusedDeclaredArtifacts: Set<ResolvedArtifact>
)

class ProjectDependencyResolver constructor(
    private val project: Project,
    private val mainClassesDirs: Iterable<File>,
    private val testClassesDirs: Iterable<File>
) {
  private val classAnalyzer = DefaultClassAnalyzer()
  private val dependencyAnalyzer = ASMDependencyAnalyzer()
  private val logger = project.logger

  fun analyzeDependencies(): ProjectDependencyAnalysis {
    val mainCompileConfig = project.configurations.named("compileClasspath")
    val testCompileConfig = project.configurations.named("testCompileClasspath")
    val implementationConfig = project.configurations.getByName("implementation")
    val testImplementationConfig = project.configurations.getByName("testImplementation")
    val permitUnusedDeclaredConfig = project.configurations.named("permitUnusedDeclared")
    val permitTestUnusedDeclaredConfig = project.configurations.named("permitTestUnusedDeclared")

    val mainDependencyDeclarationNames =
        implementationConfig.dependencies
            .map { "${it.name}-${it.version}.jar" }
            .toSet()
    val testDependencyDeclarationNames =
        testImplementationConfig.dependencies
            .map { "${it.name}-${it.version}.jar" }
            .toSet()

    val mainRequiredDeps = getFirstLevelDependencies(mainCompileConfig.get())
    val testRequiredDeps = getFirstLevelDependencies(testCompileConfig.get())
    val mainPermitUnusedDeclaredDeps = getFirstLevelDependencies(permitUnusedDeclaredConfig.get())
    val testPermitUnusedDeclaredDeps = getFirstLevelDependencies(permitTestUnusedDeclaredConfig.get())

    val mainDependencyArtifactFiles = findModuleArtifactFiles(mainRequiredDeps)
    val testDependencyArtifactFiles = findModuleArtifactFiles(testRequiredDeps)
    val mainPermitUnusedDeclaredFiles = findModuleArtifactFiles(mainPermitUnusedDeclaredDeps)
    val testPermitUnusedDeclaredFiles = findModuleArtifactFiles(testPermitUnusedDeclaredDeps)

    val testDeclaredArtifactFiles = testDependencyArtifactFiles.filter { it.name in testDependencyDeclarationNames }

    val thisProjectOutputJarNameOrNull =
        project.tasks.firstOrNull { it.name == "jar" }?.outputs?.files?.singleFile?.name

    val mainAllDependencyArtifacts = findAllModuleArtifacts(mainRequiredDeps)
    val testAllDependencyArtifacts = findAllModuleArtifacts(testRequiredDeps)

    val mainIndirectApiDependencyArtifactFiles = findIndirectApiModuleArtifactFiles(
        mainRequiredDeps,
        mainAllDependencyArtifacts
    )
    val testIndirectApiDependencyArtifactFiles = findIndirectApiModuleArtifactFiles(
        testRequiredDeps,
        testAllDependencyArtifacts
    )

    val mainFileClassMap = buildArtifactClassMap(mainAllDependencyArtifacts)
    val testFileClassMap = buildArtifactClassMap(testAllDependencyArtifacts)

    val mainDependencyClasses = getDependencyClasses(mainClassesDirs, mainAllDependencyArtifacts)
    val testDependencyClasses = getDependencyClasses(testClassesDirs, testAllDependencyArtifacts)

    val mainUsedArtifactFiles = buildUsedArtifacts(mainFileClassMap, mainDependencyClasses)
    val testUsedArtifactFiles = buildUsedArtifacts(testFileClassMap, testDependencyClasses)

    // Used and Declared
    val mainUsedDeclaredArtifactFiles = mainDependencyArtifactFiles.intersect(mainUsedArtifactFiles)
    val testUsedDeclaredArtifactFiles = testDeclaredArtifactFiles.intersect(testUsedArtifactFiles)

    // Used and Undeclared
    val mainUsedUndeclaredArtifactFiles =
        mainUsedArtifactFiles
            .minus(mainDependencyArtifactFiles)
            .minus(mainIndirectApiDependencyArtifactFiles)
            .toSet()

    val testUsedUndeclaredArtifactFiles =
        testUsedArtifactFiles
            .minus(testDependencyArtifactFiles)
            .minus(testIndirectApiDependencyArtifactFiles)
            .minus(mainUsedDeclaredArtifactFiles)
            .filter { it.name != thisProjectOutputJarNameOrNull }
            .toSet()

    // Used by Test but not Main
    val mainUnusedDeclaredButUsedByTestArtifactFiles =
        mainDependencyArtifactFiles
            .minus(mainUsedArtifactFiles)
            .minus(mainPermitUnusedDeclaredFiles)
            .intersect(testUsedArtifactFiles)

    // Unused Declared
    val mainUnusedDeclaredArtifactFiles =
        mainDependencyArtifactFiles
            .minus(mainUsedArtifactFiles)
            .minus(mainPermitUnusedDeclaredFiles)
            .minus(mainUnusedDeclaredButUsedByTestArtifactFiles)
    val testUnusedDeclaredArtifactFiles =
        testDependencyArtifactFiles
            .minus(mainUsedArtifactFiles)
            .minus(mainPermitUnusedDeclaredFiles)
            .minus(testPermitUnusedDeclaredFiles)
            .minus(testUsedArtifactFiles)
            .minus(mainUnusedDeclaredArtifactFiles)

    // Declared by Test but already Declared by Main
    val testUnnecessaryDeclarationArtifactFiles = testDeclaredArtifactFiles.intersect(mainUsedDeclaredArtifactFiles)

    // Unnecessary permitUnusedDeclared
    val mainUnnecessaryPermitUnusedDeclaredFiles = mainPermitUnusedDeclaredFiles.intersect(mainUsedArtifactFiles)
    val testUnnecessaryPermitUnusedDeclaredFiles = testPermitUnusedDeclaredFiles.intersect(testUsedArtifactFiles)

    logTitleStrings("mainDependencyDeclarationNames", mainDependencyDeclarationNames)
    logTitleStrings("testDependencyDeclarationNames", testDependencyDeclarationNames)
    logTitleStrings("mainRequiredDeps", mainRequiredDeps.map { it.name }.toSet())
    logTitleStrings("testRequiredDeps", testRequiredDeps.map { it.name }.toSet())
    logTitleStrings("mainDependencyArtifactFiles", mainDependencyArtifactFiles.map { it.name }.toSet())
    logTitleStrings("testDependencyArtifactFiles", testDependencyArtifactFiles.map { it.name }.toSet())
    logTitleStrings(
        "mainIndirectApiDependencyArtifactFiles",
        mainIndirectApiDependencyArtifactFiles.map { it.name }.toSet()
    )
    logTitleStrings(
        "testIndirectApiDependencyArtifactFiles",
        testIndirectApiDependencyArtifactFiles.map { it.name }.toSet()
    )
    logTitleStrings("mainAllDependencyArtifacts", mainAllDependencyArtifacts.map { it.name }.toSet())
    logTitleStrings("testAllDependencyArtifacts", testAllDependencyArtifacts.map { it.name }.toSet())
    logTitleStrings("mainUsedArtifactFiles", mainUsedArtifactFiles.map { it.name }.toSet())
    logTitleStrings("testUsedArtifactFiles", testUsedArtifactFiles.map { it.name }.toSet())
    logTitleStrings("mainUsedDeclaredArtifactFiles", mainUsedDeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("testUsedDeclaredArtifactFiles", testUsedDeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("mainUsedUndeclaredArtifactFiles", mainUsedUndeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("testUsedUndeclaredArtifactFiles", testUsedUndeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("mainUnusedDeclaredArtifactFiles", mainUnusedDeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("testUnusedDeclaredArtifactFiles", testUnusedDeclaredArtifactFiles.map { it.name }.toSet())
    logTitleStrings("mainPermitUnusedDeclaredFiles", mainPermitUnusedDeclaredFiles.map { it.name }.toSet())
    logTitleStrings("testPermitUnusedDeclaredFiles", testPermitUnusedDeclaredFiles.map { it.name }.toSet())
    logTitleStrings(
        "mainUnnecessaryPermitUnusedDeclaredFiles",
        mainUnnecessaryPermitUnusedDeclaredFiles.map { it.name }.toSet()
    )
    logTitleStrings(
        "testUnnecessaryPermitUnusedDeclaredFiles",
        testUnnecessaryPermitUnusedDeclaredFiles.map { it.name }.toSet()
    )
    logTitleStrings(
        "mainUnusedDeclaredButUsedByTestArtifactFiles",
        mainUnusedDeclaredButUsedByTestArtifactFiles.map { it.name }.toSet()
    )
    logTitleStrings(
        "testUnnecessaryDeclarationArtifactFiles",
        testUnnecessaryDeclarationArtifactFiles.map { it.name }.toSet()
    )

    val mainUsedUndeclared = mainAllDependencyArtifacts
        .filter { it.file in mainUsedUndeclaredArtifactFiles }
        .toSet()
    val testUsedUndeclared =
        testAllDependencyArtifacts
            .filter { it.file in testUsedUndeclaredArtifactFiles }
            .toSet()

    val mainUnusedDeclaredButUsedByTest =
        mainAllDependencyArtifacts
            .filter { it.file in mainUnusedDeclaredButUsedByTestArtifactFiles }
            .toSet()
    val mainUnusedDeclared = mainAllDependencyArtifacts.filter { it.file in mainUnusedDeclaredArtifactFiles }.toSet()
    val testUnusedDeclared = testAllDependencyArtifacts.filter { it.file in testUnusedDeclaredArtifactFiles }.toSet()
    val testUnnecessaryDeclarations =
        testAllDependencyArtifacts
            .filter { it.file in testUnnecessaryDeclarationArtifactFiles }
            .toSet()

    val mainUnnecessaryPermitUnusedDeclared =
        mainAllDependencyArtifacts
            .filter { it.file in mainUnnecessaryPermitUnusedDeclaredFiles }
            .toSet()
    val testUnnecessaryPermitUnusedDeclared =
        testAllDependencyArtifacts
            .filter { it.file in testUnnecessaryPermitUnusedDeclaredFiles }
            .toSet()

    return ProjectDependencyAnalysis(
        mainUsedUndeclared,
        testUsedUndeclared,
        mainUnusedDeclaredButUsedByTest,
        mainUnusedDeclared,
        testUnusedDeclared,
        testUnnecessaryDeclarations,
        mainUnnecessaryPermitUnusedDeclared,
        testUnnecessaryPermitUnusedDeclared
    )
  }

  private fun getDependencyClasses(
      classesDirs: Iterable<File>,
      allDependencyArtifacts: Set<ResolvedArtifact>
  ):
      Set<String> {
    // Resource URLs are the list of full paths to source directories and dependency artifacts.
    val resourceUrls = classesDirs.map { it.toURI().toURL() }
        .plus(allDependencyArtifacts.map { it.file.toURI().toURL() })

    // Opening a class loader that references all sources and dependencies because we need to use reflection
    // to determine the supertype of each class.
    val classLoader: ClassLoader = URLClassLoader(resourceUrls.toTypedArray())

    // For each classdir, we extract all the classes found within.
    val classes = classesDirs.flatMap {
      val dirUrl = it.toURI().toURL()
      logger.debug("Analyzing dir $dirUrl")
      val depClasses = dependencyAnalyzer.analyze(dirUrl)
      logger.debug("Got classes $depClasses")
      depClasses
    }.toSet()

    // We extract all the superclasses because often kotlin compile requires a declared dependency on the package
    // that includes the supertype of the type being used.
    val superclasses = classes.flatMap { findSupertypesForClass(it, classLoader) }
    return classes.plus(superclasses)
  }

  private fun logTitleStrings(title: String, strings: Set<String>) {
    logger.info("\n\n$title")
    strings.forEach { logger.info("-$it") }
  }

  private fun getFirstLevelDependencies(configuration: Configuration): Set<ResolvedDependency> {
    return configuration.resolvedConfiguration.firstLevelModuleDependencies.toSet()
  }

  private fun buildArtifactClassMap(dependencyArtifacts: Set<ResolvedArtifact>): Map<File, Set<String>> {
    return dependencyArtifacts
        .map { it.file }
        .filter { it.name.endsWith("jar") }
        .associateWith { classAnalyzer.analyze(it.toURI().toURL()) }
  }

  private fun findIndirectApiModuleArtifacts(
      inputArtifacts: Set<ResolvedArtifact>,
      allArtifacts: Set<ResolvedArtifact>
  ): Set<ResolvedArtifact> {
    val artifactNames =
        inputArtifacts.map { it.id.componentIdentifier }
            // Filters to dependencies that are other projects, verses third party dependencies.
            .filterIsInstance<ProjectComponentIdentifier>()
            // Maps to the actual Gradle project of the dependency.
            .map { project.project(it.projectPath) }
            // The "api" config from each project.
            .map { it.configurations.getByName("api") }
            // The "api" dependencies.
            .flatMap { it.dependencies }
            // Mapping to the artifact name.
            .map { "${it.name}-${it.version}.jar" }
            .toSet()

    val outputArtifacts = allArtifacts.filter { it.file.name in artifactNames }.toSet()
    return if (outputArtifacts.isEmpty()) {
      emptySet()
    } else {
      outputArtifacts.plus(findIndirectApiModuleArtifacts(outputArtifacts, allArtifacts))
    }
  }

  private fun findIndirectApiModuleArtifactFiles(
      dependencies: Set<ResolvedDependency>,
      allArtifacts: Set<ResolvedArtifact>
  ): Set<File> {
    return findIndirectApiModuleArtifacts(
        dependencies.flatMap { it.moduleArtifacts }.toSet(),
        allArtifacts
    ).map { it.file }.toSet()
  }

  private fun findModuleArtifactFiles(dependencies: Set<ResolvedDependency>): Set<File> {
    return dependencies.flatMap { it.moduleArtifacts }.map { it.file }.toSet()
  }

  private fun findAllModuleArtifacts(dependencies: Set<ResolvedDependency>): Set<ResolvedArtifact> {
    return dependencies.flatMap { it.allModuleArtifacts }.toSet()
  }

  private fun findSupertypesForClass(className: String, classLoader: ClassLoader): Set<String> {
    try {
      val itClass = Class.forName(className, false, classLoader)
      val parentReferences = itClass.interfaces.map { it.name }.toSet().plus(itClass.superclass.name)
      return parentReferences
    } catch (e: Throwable) {
      // Swallow
      return setOf()
    }
  }

  private fun buildUsedArtifacts(artifactClassMap: Map<File, Set<String>>, dependencyClasses: Set<String>): Set<File> {
    return dependencyClasses.map { className ->
      artifactClassMap.entries.firstOrNull { it.value.contains(className) }?.key
    }.filterNotNull().toSet()
  }
}
