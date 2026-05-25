plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}

allprojects {
    group = "com.jbaruch.jclaw"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper> {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
    }
}

// Root-level `./gradlew run` → j-claw demo end-to-end.
// Builds & installs both mock MCP servers (via :jclaw-koog:run's own dependsOn),
// then launches the agent.
tasks.register("run") {
    group = "application"
    description = "Run j-claw end-to-end (builds & launches both mock MCP servers, then the agent)"
    dependsOn(":jclaw-koog:run")
}
