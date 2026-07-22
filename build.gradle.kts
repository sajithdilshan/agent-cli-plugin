import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
    id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

/** Machine-local defaults (gitignored); see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html */
val localProperties =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use(::load)
    }

fun signingCertificateChainFile(): String? =
    System.getenv("PLUGIN_SIGNING_CERT_CHAIN_FILE")
        ?: localProperties.getProperty("pluginSigning.certificateChainFile")
        ?: findProperty("pluginSigning.certificateChainFile") as String?

fun signingPrivateKeyFile(): String? =
    System.getenv("PLUGIN_SIGNING_PRIVATE_KEY_FILE")
        ?: localProperties.getProperty("pluginSigning.privateKeyFile")
        ?: findProperty("pluginSigning.privateKeyFile") as String?

fun signingPrivateKeyPassword(): String? =
    System.getenv("PLUGIN_SIGNING_PRIVATE_KEY_PASSWORD")
        ?: localProperties.getProperty("pluginSigning.privateKeyPassword")
        ?: findProperty("pluginSigning.privateKeyPassword") as String?

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("buildPlugin") {
    dependsOn(tasks.test)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Emit real JVM default methods instead of DefaultImpls bridges. Without this, Kotlin
        // generates synthetic references to every default method on implemented platform
        // interfaces (e.g. ToolWindowFactory.getAnchor/getIcon/manage), which the Marketplace
        // verifier flags as experimental/deprecated API usage the plugin never actually calls.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        val chain = signingCertificateChainFile()
        val key = signingPrivateKeyFile()
        if (chain != null && key != null) {
            certificateChainFile.set(file(chain))
            privateKeyFile.set(file(key))
            signingPrivateKeyPassword()?.let { password.set(it) }
        }
    }

    publishing {
        token = System.getenv("PUBLISH_TOKEN")
        // Route by version suffix: "1.0.0" -> default (stable); "1.1.0-eap.2" -> "eap"; "1.1.0-beta" -> "beta".
        val versionValue = providers.gradleProperty("pluginVersion").get()
        val channel = versionValue.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }
        channels = listOf(channel)
    }
}
