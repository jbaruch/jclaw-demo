plugins { application }

dependencies {
    implementation(project(":tui"))
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.koog.mcp)
    implementation(libs.koog.otel)
    implementation(libs.koog.memory)
    implementation(libs.koog.rag.vector)
    implementation(libs.koog.embeddings)
    implementation(libs.kotlinx.coroutines)
    // Koog logs through SLF4J. Without a provider its warnings - a failed telemetry
    // export, say - go nowhere. simple-logger prints them to stderr; the TUI files stderr.
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass.set("jclaw.MainKt")
    // JLine loads a native library; without this the JVM prints a four-line warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}

tasks.named<JavaExec>("run") {
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    systemProperty("jclaw.memory", rootProject.layout.projectDirectory.dir("memory").asFile.absolutePath)
    standardInput = System.`in`
}

/** The three-pane TUI front end over the same agent: `gradle :app:runTui`. */
tasks.register<JavaExec>("runTui") {
    group = "application"
    mainClass.set("jclaw.TuiKt")
    classpath = sourceSets.main.get().runtimeClasspath
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    systemProperty("jclaw.memory", rootProject.layout.projectDirectory.dir("memory").asFile.absolutePath)
    standardInput = System.`in`
}

// The application plugin scripts only mainClass. On stage the TUI must run without
// Gradle in the loop, so it gets its own launcher next to `app`.
val tuiScripts = tasks.register<CreateStartScripts>("startScripts_app-tui") {
    applicationName = "app-tui"
    mainClass.set("jclaw.TuiKt")
    defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
    outputDir = layout.buildDirectory.dir("scripts-app-tui").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}
tasks.named<Sync>("installDist") { into("bin") { from(tuiScripts) { fileMode = 493 } } }
