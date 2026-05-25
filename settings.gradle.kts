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
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "ossrh-snapshots"
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "jclaw-demo"

include(":mocks:calendar-mcp")
include(":mocks:organizer-mcp")
include(":jclaw-koog")
// Coming next:
// include(":tui")              // TamboUI TUI shell — building console-first, swap later
// include(":mocks:seed-memory") // sqlite seed program — using in-process seed for now
// Viktor's side — uncomment when his code lands
// include(":jclaw-lc4j")
