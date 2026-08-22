import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

fun <T : ModuleDependency> T.excludeKotlin() {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
}

dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)

    api(libs.ytmkt) { 
        excludeKotlin()
    }  
    implementation(libs.newpipe) { excludeKotlin() }
    implementation(libs.ktor.client.core) { excludeKotlin() }
    implementation(libs.ktor.client.cio) { excludeKotlin() }
    implementation(libs.ktor.client.content.negotiation) { excludeKotlin() }
    implementation(libs.ktor.serialization.kotlinx.json) { excludeKotlin() }

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
}

// Extension properties goto `gradle.properties` to set values

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName

            from(components["java"])
        }
    }
}

tasks {
    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)
        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,

                    "Extension-Version-Code" to verCode,
                    "Extension-Version-Name" to verName,

                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,

                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,

                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
    
    // Enable test tasks now that we have proper implementations
    test {
        enabled = true
        // Live-network diagnostics hit YouTube directly and only print failures,
        // so keep them out of the default test run. Opt in with:
        //   ./gradlew :ext:test -PincludeDiagnostics
        if (!providers.gradleProperty("includeDiagnostics").isPresent) {
            filter {
                excludeTestsMatching("*ArtistNameDiagnostic")
                excludeTestsMatching("*PlaybackFlowDiagnostic")
                isFailOnNoMatchingTests = false
            }
        }
    }

    compileTestKotlin {
        enabled = true
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()