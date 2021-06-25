# gradle-kotlin-buildozer
Find unnecessary Gradle build file dependencies in Kotlin projects. Remove unnecessary dependencies to keep your builds as fast and parallel as possible!

This plugin is basically a fork of https://github.com/gradle-dependency-analyze/gradle-dependency-analyze but with the following modifications
1. All source rewritten from Groovy to Kotlin
2. Added a full set of unit tests to allow faster / more confident iteration on the features
3. Better support for Kotlin projects
4. Additional features and configurability

Thanks to https://gist.github.com/wfhartford and https://github.com/kellyrob99 for the inspiration!

# Installation
The plugin is available from the gradle plugin repository. See the instructions here for how to include it in your project.
https://plugins.gradle.org/plugin/com.faire.gradle.analyze

# Usage
```
allprojects {
  apply<com.faire.gradle.analyze.AnalyzeDependenciesPlugin>()
}
```

Applying this project adds the task `analyzeDependencies` to your projects. 

## Example output
When you have an extra dependency
```
Execution failed for task ':core:analyzeDependencies'.
> Dependency analysis found issues.
  Main dependencies not used, but declared in gradle -- remove implementation()/api() references in build.gradle.kts: 
   - com.faire:core-jobs:1.0.SNAPSHOT@jar
```

When you have a test dependency listed as an implementation dependency
```
Execution failed for task ':core:analyzeDependencies'.
> Dependency analysis found issues.
  Main dependencies not used, but are used by test -- e.g. change implementation() to testImplementation() in build.gradle.kts: 
   - com.faire:core-test:1.0.SNAPSHOT@jar
```

Unnecessary test dependency
```
Execution failed for task ':core:analyzeDependencies'.
> Dependency analysis found issues.
  Test dependencies already declared by main -- remove testImplementation()/testApi() references in build.gradle.kts: 
   - com.faire:core-persistence:1.0.SNAPSHOT@jar
```


## Options

### justWarn
If true, all errors from the analyze task will be treated as warnings. This is useful when integrating with a new project that has many errors. If not used, the task will abort upon the first error found. Defaults to false.

```
allprojects {
  apply<com.faire.gradle.analyze.AnalyzeDependenciesPlugin>()
  configure<com.faire.gradle.analyze.AnalyzeDependenciesPluginExtension> {
    justWarn = false
  }
}
```

### includedPackageFilters
Set of package filters to apply to dependencies for error checking. Dependencies that don't match the set of package filters will be ignored in analysis.

```
allprojects {
  apply<com.faire.gradle.analyze.AnalyzeDependenciesPlugin>()
  configure<com.faire.gradle.analyze.AnalyzeDependenciesPluginExtension> {
    includedPackageFilters = listOf("com.faire")
  }
}
```

# Limitations
## Unrecognized Dependencies

There are some dependencies that cannot be detected by inspecting the generated class files. In these cases, the tool will prompt you to remove a dependency, but removing it will result in a compile error. For this you can use `permitUnusedDeclared` and `permitTestUnusedDeclared` to work around it.
```
permitUnusedDeclared(project(":core:core-session"))
```

Thankfully, the tool will notify you when it is no longer necessary to include these workarounds (e.g. if you rearrange the code)
```
Execution failed for task ':core:analyzeDependencies'.
> Dependency analysis found issues.
  Main dependency is listed as permitUnusedDeclared() but does not need to be -- remove its permitUnusedDeclared() configuration in build.gradle.kts: 
   - com.faire:core-persistence:1.0.SNAPSHOT@jar
```

## Toolchains
If your project is using toolchains (i.e. https://docs.gradle.org/current/userguide/toolchains.html), then it is currenty incompatible with this plugin.

