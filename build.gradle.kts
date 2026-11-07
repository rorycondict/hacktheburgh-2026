import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm")
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}

// Plugin content modules. `acp-tap` is a plain JVM app and configures itself.
val pluginModules = setOf("shared", "frontend", "backend")

subprojects {
    if (name in pluginModules) {
        apply(plugin = "org.jetbrains.intellij.platform.module")
        apply(plugin = "rpc")
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2026.2.4")

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}

// Ship the ACP tap fat jar inside the plugin: <plugin dir>/tap/acp-tap.jar
val acpTapJar = configurations.create("acpTapJar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    acpTapJar(project(path = ":acp-tap", configuration = "tapJar"))
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(acpTapJar) {
        into(pluginName.map { "$it/tap" })
        rename { "acp-tap.jar" }
    }
}
