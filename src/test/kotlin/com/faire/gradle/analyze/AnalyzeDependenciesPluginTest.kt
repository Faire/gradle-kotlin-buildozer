package com.faire.gradle.analyze

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

val KOTLIN_VERSION = "1.4.30"

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

val MAIN_UNUSED_ERROR_LINE =
  "  Main dependencies not used, but declared in gradle -- remove implementation()/api() references in build.gradle.kts: "

val TEST_UNUSED_ERROR_LINE =
  "  Test dependencies not used, but declared in gradle -- remove testImplementation()/testApi() references in build.gradle.kts: "

val MAIN_UNUSED_DECLARED_BY_TEST_LINE =
  "  Main dependencies not used, but are used by test -- e.g. change implementation() to testImplementation() in build.gradle.kts: "

val REMOVE_PERMIT_TEST_LINE =
  "  Test dependency is listed as permitTestUnusedDeclared() but does not need to be -- remove its permitTestUnusedDeclared() configuration in build.gradle.kts: "

val REMOVE_PERMIT_MAIN_LINE =
  "  Main dependency is listed as permitUnusedDeclared() but does not need to be -- remove its permitUnusedDeclared() configuration in build.gradle.kts: "

val UNNECESSARY_TEST_LINE =
  "  Test dependencies already declared by main -- remove testImplementation()/testApi() references in build.gradle.kts: "

val BOILERPLATE_ERROR_LINES = 7

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
    assertThat(failureListingLines[1]).isEqualTo(UNNECESSARY_TEST_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(UNNECESSARY_TEST_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(MAIN_UNUSED_ERROR_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(TEST_UNUSED_ERROR_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(MAIN_UNUSED_DECLARED_BY_TEST_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(MAIN_UNUSED_DECLARED_BY_TEST_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(REMOVE_PERMIT_MAIN_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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
    assertThat(failureListingLines[1]).isEqualTo(REMOVE_PERMIT_TEST_LINE)
    assertThat(failureListingLines[2]).isEqualTo("   - com.faire:moduleA:1.0@jar")
    assertThat(failureListingLines.size).isEqualTo(BOILERPLATE_ERROR_LINES + 2)
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

  @Test
  fun `Parent directory without a build file`() {
    val parentDir = File(testProjectDir.root, "parent")
    parentDir.mkdirs()
    val moduleOne = File(parentDir, "moduleOne")
    val moduleTwo = File(parentDir, "moduleTwo")
    moduleOne.mkdirs()
    moduleTwo.mkdirs()

    val srcDirA = File(moduleOne, "src/main/java")
    val srcDirB = File(moduleTwo, "src/main/java")
    srcDirA.mkdirs()
    srcDirB.mkdirs()
    File(srcDirA, "AFoo.java").writeText(
      """
       package com.faire.a;
       public class AFoo {
       }
     """.trimIndent()
    )
    createBuildFileWithDependencies(moduleOne, listOf())

    File(srcDirB, "BFoo.java").writeText(
      """
       package com.faire.b;
       import com.faire.a.AFoo;

       public class BFoo {
         BFoo(AFoo a) {}
       }
     """.trimIndent()
    )
    createBuildFileWithDependencies(moduleTwo, listOf(":parent:moduleOne"))

    createGradleSettingsFileWithModuleIncludes(testProjectDir.root, listOf(":parent:moduleOne", ":parent:moduleTwo"))

    val result = GradleRunner.create()
      .withProjectDir(testProjectDir.root)
      .withArguments("analyzeDependencies")
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
        $BUILDSCRIPT
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
