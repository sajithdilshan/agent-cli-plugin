import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

/** Machine-local defaults (gitignored); see https://plugins.jetbrains.com/docs/intellij/plugin-signing.html */
val localProperties = Properties().apply {
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

intellij {
    version.set(providers.gradleProperty("platformVersion"))
    type.set(providers.gradleProperty("platformType"))
    plugins.set(emptyList())
}

kotlin {
    jvmToolchain(17)
}

tasks {
    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("262.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    signPlugin {
        val chain = signingCertificateChainFile()
        val key = signingPrivateKeyFile()
        if (chain != null && key != null) {
            certificateChainFile.set(file(chain))
            privateKeyFile.set(file(key))
            signingPrivateKeyPassword()?.let { password.set(it) }
        }
    }
}
