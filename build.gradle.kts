plugins {
  kotlin("jvm") version Versions.kotlin
  `kotlin-dsl`
  `java-gradle-plugin`
  id("com.gradle.plugin-publish") version "0.14.0"
}

allprojects {
  group = "com.faire.gradle"
  version = "1.0.3"

  repositories {
    mavenCentral()
  }
}

java {
  withJavadocJar()
  withSourcesJar()
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("stdlib-jdk8"))

  implementation("org.apache.maven.shared:maven-dependency-analyzer:1.11.2")

  testImplementation("junit:junit:4.12")
  testImplementation("org.assertj:assertj-core:3.9.1")
}

gradlePlugin {
  plugins {
    create("kotlinBuildozer") {
      id = "com.faire.gradle.analyze"
      displayName = "Gradle Kotlin Buildozer (dependency cleaner)"
      description = "Detects missing or superfluous build dependencies in Kotlin projects"
      implementationClass = "com.faire.gradle.analyze.AnalyzeDependenciesPlugin"
    }
  }
}

pluginBundle {
  website = "https://github.com/Faire/gradle-kotlin-buildozer"
  vcsUrl = "https://github.com/Faire/gradle-kotlin-buildozer"
  tags = listOf("dependency", "verification", "analyze")
}
