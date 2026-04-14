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

kotlin {
    jvmToolchain(17)
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
}
