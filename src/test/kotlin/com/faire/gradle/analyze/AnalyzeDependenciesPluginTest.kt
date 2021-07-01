package com.faire.gradle.analyze

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

val KOTLIN_VERSION = "1.4.10"

val BUILDSCRIPT = """
    buildscript {
      repositories {
        mavenCentral()
      }
      dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${KOTLIN_VERSION}")
      }
    }
  """

class AnalyzeDependenciesPluginTest {
  @get:Rule
  val testProjectDir = TemporaryFolder()

  private lateinit var directoryModuleA: File
  private lateinit var directoryModuleB: File
  private lateinit var directoryModuleC: File
  private lateinit var directoryModuleD: File

  @Before
  fun setUp() {
    directoryModuleA = File(testProjectDir.root, "moduleA")
    directoryModuleA.mkdirs()
    directoryModuleB = File(testProjectDir.root, "moduleB")
    directoryModuleB.mkdirs()
    directoryModuleC = File(testProjectDir.root, "moduleC")
    directoryModuleC.mkdirs()
    directoryModuleD = File(testProjectDir.root, "moduleD")
    directoryModuleD.mkdirs()

    createBuildFileForKotlinProjectUsingPlugin(
      testProjectDir.root
    )
  }

  @Test
  fun `Proper main dependency no problems`() {
    val srcDirA = File(directoryModuleA, "src/main/java")
    val srcDirB = File(directoryModuleB, "src/main/java")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFoo {
        BFoo(AFoo a) {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Proper test dependency no problems`() {
    val srcDirA = File(directoryModuleA, "src/main/java/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/test/java/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFooTest.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFooTest {
        BFooTest(AFoo a) {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(), testDependencies = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Valid main dependency and redundant test dependency (test doesn't also use)`() {
    val srcDirA = File(directoryModuleA, "src/main/java/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/java/com/faire/b")
    val testDirB = File(directoryModuleB, "src/test/java/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    testDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
      package com.faire.b;
      
      import com.faire.a.AFoo;
      
      public class BFoo {
        BFoo(AFoo aFoo) {}
      }
    """.trimIndent()
    )
    File(testDirB, "BFooTest.java").writeText(
      """
      package com.faire.b;
      
      public class BFooTest {
        BFooTest() {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"), testDependencies = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  testUnnecessaryDeclarations: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Valid main dependency and redundant test dependency (test also uses)`() {
    val srcDirA = File(directoryModuleA, "src/main/java/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/java/com/faire/b")
    val testDirB = File(directoryModuleB, "src/test/java/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    testDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFoo {
        BFoo(AFoo a) {}
      }
    """.trimIndent()
    )
    File(testDirB, "BFooTest.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFooTest {
        BFooTest(AFoo a) {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"), testDependencies = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  testUnnecessaryDeclarations: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Extra main dependency`() {
    val srcDirA = File(directoryModuleA, "src/main/java/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/java/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
          package com.faire.a;
          public class AFoo {
          }
        """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
          package com.faire.b;
          
          public class BFoo {
          }
        """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  mainUnusedDeclaredArtifacts: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Extra test dependency`() {
    val srcDirA = File(directoryModuleA, "src/main/java/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/test/java/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
          package com.faire.a;
          public class AFoo {
          }
        """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFooTest.java").writeText(
      """
          package com.faire.b;
          
          public class BFooTest {
          }
        """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(), testDependencies = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  testUnusedDeclaredArtifacts: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Transitive dependency via superclass`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    val srcDirC = File(directoryModuleC, "src/main/kotlin/com/faire/c")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    srcDirC.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          interface AFoo {
            fun execute()
          }
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo : AFoo {
            override fun execute() {}
          }
        """.trimIndent()
    )
    File(srcDirC, "CFoo.kt").writeText(
      """
      package com.faire.c
      import com.faire.b.BFoo
      
      class CFoo constructor(private val a: BFoo) {
        fun someMethod() {
          a.execute()
        }
      }
    """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"))
    createBuildFileWithDependencies(directoryModuleC, listOf(":moduleA", ":moduleB"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB", "moduleC"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Move to test`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/test/kotlin/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun execute(a: AFoo) {
              println(a)
            }
          }
        """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  mainUnusedDeclaredButUsedByTest: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Move to test when already declared in test`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/test/kotlin/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun execute(a: AFoo) {
              println(a)
            }
          }
        """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"), testDependencies = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  mainUnusedDeclaredButUsedByTest: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Permit unused declared when necessary`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          
          object AConstants {
            const val FOO: Long = 1
          }
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AConstants
          
          class BFoo {
            fun execute() {
              println(AConstants.FOO)
            }
          }
        """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"), permitUnusedDeclared = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Permit test unused declared when necessary`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val testDirB = File(directoryModuleB, "src/test/kotlin/com/faire/b")
    srcDirA.mkdirs()
    testDirB.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          
          object AConstants {
            const val FOO: Long = 1
          }
        """.trimIndent()
    )
    File(testDirB, "BFooTest.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AConstants
          
          class BFooTest {
            fun execute() {
              println(AConstants.FOO)
            }
          }
        """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(
      directoryModuleB,
      listOf(),
      testDependencies = listOf(":moduleA"),
      permitTestUnusedDeclared = listOf(":moduleA")
    )

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Unnecessary permitUnusedDeclared`() {
    val srcDirA = File(directoryModuleA, "src/main/java")
    val srcDirB = File(directoryModuleB, "src/main/java")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFoo {
        BFoo(AFoo a) {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleB, listOf(":moduleA"), permitUnusedDeclared = listOf(":moduleA"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  mainUnnecessaryPermitUnusedDeclaredArtifacts: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Unnecessary permitTestUnusedDeclared`() {
    val srcDirA = File(directoryModuleA, "src/main/java")
    val srcDirB = File(directoryModuleB, "src/test/java")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
      package com.faire.a;
      public class AFoo {
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(directoryModuleA, listOf())

    File(srcDirB, "BFooTest.java").writeText(
      """
      package com.faire.b;
      import com.faire.a.AFoo;
      
      public class BFooTest {
        BFooTest(AFoo a) {}
      }
    """.trimIndent()
    )
    createBuildFileWithDependencies(
      directoryModuleB,
      listOf(),
      testDependencies = listOf(":moduleA"),
      permitTestUnusedDeclared = listOf(":moduleA")
    )

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .buildAndFail()

    assertThat(result.output).contains("Execution failed for task ':moduleB:analyzeDependencies'.")
    val buildOutputSuffix =
      result.output.substringAfterLast("Execution failed for task ':moduleB:analyzeDependencies'.")
    val failureListingLines = buildOutputSuffix.lines().filter { it.isNotEmpty() }
    assertThat(failureListingLines[0]).isEqualTo("> Dependency analysis found issues.")
    assertThat(failureListingLines[1]).isEqualTo("  testUnnecessaryPermitUnusedDeclaredArtifacts: ")
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(8)
  }

  @Test
  fun `Unnecessary to declare indirect dependencies when api() is used`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    val srcDirC = File(directoryModuleC, "src/main/kotlin/com/faire/c")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    srcDirC.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun someMethod(a: AFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirC, "CFoo.kt").writeText(
      """
      package com.faire.c
      
      import com.faire.a.AFoo
      import com.faire.b.BFoo
      
      class CFoo {
        fun someMethod(a: AFoo, b: BFoo) {}
      }
    """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(), apiDependencies = listOf(":moduleA"))
    createBuildFileWithDependencies(directoryModuleC, listOf(":moduleB"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB", "moduleC"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Unnecessary to declare indirect dependencies when api() is used (test)`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    val srcDirC = File(directoryModuleC, "src/test/kotlin/com/faire/c")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    srcDirC.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun someMethod(a: AFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirC, "CFoo.kt").writeText(
      """
      package com.faire.c
      
      import com.faire.a.AFoo
      import com.faire.b.BFoo
      
      class CTestFoo {
        fun someMethod(a: AFoo, b: BFoo) {}
      }
    """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(), apiDependencies = listOf(":moduleA"))
    createBuildFileWithDependencies(directoryModuleC, listOf(), testDependencies = listOf(":moduleB"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf("moduleA", "moduleB", "moduleC"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Unnecessary to declare indirect dependencies when api() is used (recursive)`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    val srcDirC = File(directoryModuleC, "src/main/kotlin/com/faire/c")
    val srcDirD = File(directoryModuleD, "src/main/kotlin/com/faire/d")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    srcDirC.mkdirs()
    srcDirD.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun someMethod(a: AFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirC, "CFoo.kt").writeText(
      """
          package com.faire.c
          
          import com.faire.a.AFoo
          import com.faire.b.BFoo
          
          class CFoo {
            fun someMethod(a: AFoo, b: BFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirD, "DFoo.kt").writeText(
      """
      package com.faire.d
      
      import com.faire.a.AFoo
      import com.faire.b.BFoo
      import com.faire.c.CFoo
      
      class DFoo {
        fun someMethod(a: AFoo, b: BFoo, c: CFoo) {}
      }
    """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(), apiDependencies = listOf(":moduleA"))
    createBuildFileWithDependencies(directoryModuleC, listOf(), apiDependencies = listOf(":moduleB"))
    createBuildFileWithDependencies(directoryModuleD, listOf(":moduleC"))

    createGradleSettingsFileWithModuleIncludes(
      testProjectDir.root,
      listOf("moduleA", "moduleB", "moduleC", "moduleD")
    )

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }

  @Test
  fun `Unnecessary to declare indirect dependencies when api() is used (test - recursive)`() {
    val srcDirA = File(directoryModuleA, "src/main/kotlin/com/faire/a")
    val srcDirB = File(directoryModuleB, "src/main/kotlin/com/faire/b")
    val srcDirC = File(directoryModuleC, "src/main/kotlin/com/faire/c")
    val srcDirD = File(directoryModuleD, "src/test/kotlin/com/faire/d")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    srcDirC.mkdirs()
    srcDirD.mkdirs()
    File(srcDirA, "AFoo.kt").writeText(
      """
          package com.faire.a
          class AFoo {}
        """.trimIndent()
    )
    File(srcDirB, "BFoo.kt").writeText(
      """
          package com.faire.b
          
          import com.faire.a.AFoo
          
          class BFoo {
            fun someMethod(a: AFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirC, "CFoo.kt").writeText(
      """
          package com.faire.c
          
          import com.faire.a.AFoo
          import com.faire.b.BFoo
          
          class CFoo {
            fun someMethod(a: AFoo, b: BFoo) {}
          }
        """.trimIndent()
    )
    File(srcDirD, "DFoo.kt").writeText(
      """
      package com.faire.d
      
      import com.faire.a.AFoo
      import com.faire.b.BFoo
      import com.faire.c.CFoo
      
      class DTestFoo {
        fun someMethod(a: AFoo, b: BFoo, c: CFoo) {}
      }
    """.trimIndent()
    )

    createBuildFileWithDependencies(directoryModuleA, listOf())
    createBuildFileWithDependencies(directoryModuleB, listOf(), apiDependencies = listOf(":moduleA"))
    createBuildFileWithDependencies(directoryModuleC, listOf(), apiDependencies = listOf(":moduleB"))
    createBuildFileWithDependencies(directoryModuleD, listOf(), testDependencies = listOf(":moduleC"))

    createGradleSettingsFileWithModuleIncludes(
      testProjectDir.root,
      listOf("moduleA", "moduleB", "moduleC", "moduleD")
    )

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies", "--info")
      .withPluginClasspath()
      .build()

    assertThat(result.output).contains("BUILD SUCCESSFUL")
  }
}

internal fun createGradleSettingsFileWithModuleIncludes(directory: File, modules: List<String>) {
  File(directory, "settings.gradle").writeText(
    """
        include(
        ${modules.map { "\"${it}\"" }.joinToString(separator = ",")}
       )
        """.trimIndent()
  )
}

internal fun createBuildFileWithDependencies(
  directory: File,
  dependencies: List<String>,
  apiDependencies: List<String> = listOf(),
  testDependencies: List<String> = listOf(),
  permitUnusedDeclared: List<String> = listOf(),
  permitTestUnusedDeclared: List<String> = listOf(),
) {
  File(directory, "build.gradle.kts").writeText(
    """
        $BUILDSCRIPT
        dependencies {
          ${dependencies.map { "implementation(project(\"${it}\"))" }.joinToString(separator = "\n")}
          ${apiDependencies.map { "api(project(\"${it}\"))" }.joinToString(separator = "\n")}
          ${testDependencies.map { "testImplementation(project(\"${it}\"))" }.joinToString(separator = "\n")}
          ${permitUnusedDeclared.map { "permitUnusedDeclared(project(\"${it}\"))" }.joinToString(separator = "\n")}
          ${
      permitTestUnusedDeclared.map { "permitTestUnusedDeclared(project(\"${it}\"))" }
        .joinToString(separator = "\n")
    }
        }
        """.trimIndent()
  )
}

internal fun createBuildFileForKotlinProjectUsingPlugin(
  rootDir: File,
): File {
  return File(rootDir, "build.gradle.kts").apply {
    writeText(
      """
        buildscript {
          repositories {
            mavenCentral()
          }
          dependencies {
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${KOTLIN_VERSION}")
          }
        }

        plugins {
          kotlin("jvm") version "${KOTLIN_VERSION}"
          id("com.faire.analyze.analyzedependencies")
        }

        allprojects {
          group = "com.faire"
          version = "1.0"
          repositories {
            mavenCentral()
          }
          apply {
            plugin("org.jetbrains.kotlin.jvm")
          }
          apply(plugin = "com.faire.analyze.analyzedependencies")
          configure<com.faire.gradle.analyze.AnalyzeDependenciesPluginExtension> {
            includedPackageFilters = listOf("com.faire")
          }
        }

        """.trimIndent()
    )
  }
}
