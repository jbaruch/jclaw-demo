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

include(":mocks:conference-mcp")
include(":mocks:contacts-mcp")
include(":tui")
include(":jclaw-koog")
// Viktor's side — uncomment when his code lands
// include(":jclaw-lc4j")
