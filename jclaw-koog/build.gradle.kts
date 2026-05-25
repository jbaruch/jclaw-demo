plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation("ai.koog:koog-agents:1.0.0")
    // The MCP client + agents-ext (subgraphs) and the MCP server module are only published as
    // 1.0.0-beta in Koog's 1.0 release window.
    implementation("ai.koog:agents-mcp:1.0.0-beta")
    implementation("ai.koog:agents-ext:1.0.0-beta")
    implementation("ai.koog:agents-features-memory:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
