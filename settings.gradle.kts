pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
dependencyResolutionManagement { repositories { mavenCentral() } }

rootProject.name = "jclaw-demo"

include(":mocks", ":app")
