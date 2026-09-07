pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jclaw-demo"

include(
    ":domain",
    ":mocks",
    ":tui",
    ":round1-chatbot",
    ":round2-tools-mcp",
    ":round3-memory",
    ":round4-pipeline",
)
// Viktor's side — uncomment when his code lands
// include(":jclaw-lc4j")
