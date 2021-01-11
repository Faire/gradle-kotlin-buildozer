plugins {
    kotlin("jvm") version Versions.kotlin
    `kotlin-dsl`
    `maven-publish`
    signing
}

allprojects {
    group = "com.faire.gradle"
    version = "1.0.1-SNAPSHOT"

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

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            repositories {
                maven {
                    name = "sonatype"

                    credentials {
                        username = project.findProperty("ossrhUsername").toString()
                        password = project.findProperty("ossrhPassword").toString()
                    }
                    val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
                    val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots/")
                    url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
                }
            }

            pom {
                name.set("kotlin-buildozer")
                description.set("Find unnecessary Gradle build file dependencies in Kotlin projects")
                url.set("https://github.com/Faire/gradle-kotlin-buildozer")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        name.set("Faire Developers")
                        email.set("noreply@faire.com")
                        url.set("https://faire.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/Faire/gradle-kotlin-buildozer.git")
                    developerConnection.set("scm:git:ssh://github.com:Faire/gradle-kotlin-buildozer.git")
                    url.set("https://github.com/Faire/gradle-kotlin-buildozer")
                }
            }
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)

    isRequired = true
    sign(publishing.publications["pluginMaven"])
}
