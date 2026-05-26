plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":tui"))
    implementation("ai.koog:koog-agents:1.0.0")
    // The MCP client + agents-ext (subgraphs) and the MCP server module are only published as
    // 1.0.0-beta in Koog's 1.0 release window.
    implementation("ai.koog:agents-mcp:1.0.0-beta")
    implementation("ai.koog:agents-ext:1.0.0-beta")
    implementation("ai.koog:agents-features-memory:1.0.0")
    // 1.0.0 stable not published for this module yet — using 1.0.0-beta (per Koog team:
    // the GA-track beta, newer than the misordered 1.0.0-beta-preview7 on Maven Central
    // despite preview7's later publish timestamp).
    implementation("ai.koog:agents-features-longterm-memory:1.0.0-beta")
    implementation("ai.koog:agents-features-event-handler:1.0.0")
    implementation("ai.koog:agents-features-opentelemetry:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Route Koog's internal SLF4J logging to stderr so HTTP request/response bodies,
    // provider error details, and other debug info land in `/tmp/jclaw.err` instead of
    // being NOP'd. INFO-level default; set -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
    // when chasing a specific failure.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("com.jbaruch.jclaw.koog.MainKt")
}

// `./gradlew :jclaw-koog:run` builds & installs both mock MCP servers first
// so Main.kt can launch them as subprocesses without manual prebuild.
// Working dir is forced to repo root so Main.kt's relative paths resolve.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`   // forward terminal stdin so awaitReaction sees y/n keystrokes
    dependsOn(
        ":mocks:conference-mcp:installDist",
        ":mocks:contacts-mcp:installDist",
    )
}
