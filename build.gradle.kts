import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.flaticols.deliverypipeline"
version = "0.5.2-beta1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Prefer a locally installed IDE (no multi-GB download). The plugin uses only
// platform APIs, so any IntelliJ-based IDE works as the compile target.
val localIde = sequenceOf(
    "/Applications/IntelliJ IDEA.app",
    "/Applications/IntelliJ IDEA Ultimate.app",
    "/Applications/IntelliJ IDEA CE.app",
    "/Applications/GoLand.app",
).map(::file).firstOrNull { it.exists() }

dependencies {
    intellijPlatform {
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdea("2026.1.1")
        }
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Channel is derived from the version suffix, with any trailing build
        // number stripped:
        //   0.5.2        -> "default" (Stable)
        //   0.5.2-beta1  -> "beta"    (users subscribe via the beta repository URL)
        channels = listOf(
            version.toString().substringAfter('-', "").trimEnd { it.isDigit() }.ifEmpty { "default" }
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
