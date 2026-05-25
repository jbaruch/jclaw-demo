plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation("ai.koog:koog-agents:1.0.0")
    implementation("ai.koog:agents-mcp-server-jvm:1.0.0-beta")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Silence kotlin-logging — its NOP fallback writes a banner to stdout that corrupts JSON-RPC.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
}

application {
    mainClass.set("com.jbaruch.jclaw.mocks.ContactsMcpKt")
}
