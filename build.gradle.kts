plugins {
    kotlin("jvm") version Versions.kotlin
    `kotlin-dsl`
}

allprojects {
    group = "com.faire.gradle"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.apache.maven.shared:maven-dependency-analyzer:1.11.2")

    testImplementation("junit:junit:4.12")
    testImplementation("org.assertj:assertj-core:3.9.1")
}