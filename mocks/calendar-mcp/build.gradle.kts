plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation("ai.koog:koog-agents:1.0.0")
    // The MCP server module is published as 1.0.0-beta (Koog's stable 1.0 doesn't include it yet).
    // Pulling the JVM variant directly because Gradle's KMP resolver can't pick a JVM variant
    // when no common 1.0.0 artifact exists for this module.
    implementation("ai.koog:agents-mcp-server-jvm:1.0.0-beta")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    mainClass.set("com.jbaruch.jclaw.mocks.CalendarMcpKt")
}
