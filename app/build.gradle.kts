plugins { application }

dependencies {
    implementation("ai.koog:agents-features-memory:1.2.0")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.3")
    testImplementation("io.kotest:kotest-assertions-core:6.0.3")
    implementation(project(":domain"))
    implementation(project(":tui"))
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.koog.mcp)
    implementation(libs.koog.memory)
    implementation(libs.koog.rag.vector)
    implementation(libs.koog.embeddings)
    implementation(libs.koog.agents.cli)
    implementation(libs.koog.skills)
    implementation(libs.koog.agents.ext)
    implementation(libs.koog.otel)
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

/** Three-pane TUI front end over the same pipeline. */
tasks.register<JavaExec>("runTui") {
    group = "application"
    dependsOn(":mocks:mcpJars")
    mainClass.set("jclaw.TuiKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    systemProperty("jclaw.memory", rootProject.layout.projectDirectory.dir("memory").asFile.absolutePath)
    standardInput = System.`in`
}

/** Probe: does Codex return typed output? `./jclaw codex` */
tasks.register<JavaExec>("codexProbe") {
    group = "application"
    mainClass.set("jclaw.TypedCodexProbeKt")
    classpath = sourceSets.main.get().runtimeClasspath
}

/** Emit the pipeline diagram from the live strategy: `gradle :app:graph`. */
tasks.register<JavaExec>("graph") {
    group = "application"
    dependsOn(":mocks:mcpJars")
    mainClass.set("jclaw.GraphKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    // JavaExec runs in the module dir; write to the repo root where it is expected.
    systemProperty("jclaw.graph.out", rootProject.layout.projectDirectory.file("pipeline.mmd").asFile.absolutePath)
}

/** Optional standalone runner; normal chat also discovers and applies skills. */
tasks.register<JavaExec>("runSkills") {
    group = "application"
    mainClass.set("jclaw.SkillsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    systemProperty("jclaw.memory", rootProject.layout.projectDirectory.dir("memory").asFile.absolutePath)
    standardInput = System.`in`
}

// The application plugin scripts only mainClass. The demo has four entry points, and
// on stage every one of them must run without Gradle in the loop.
listOf(
    "app-tui" to "jclaw.TuiKt",
    "app-skills" to "jclaw.SkillsKt",
    "app-graph" to "jclaw.GraphKt",
    "app-codex" to "jclaw.TypedCodexProbeKt",
).forEach { (scriptName, main) ->
    val t = tasks.register<CreateStartScripts>("startScripts_$scriptName") {
        applicationName = scriptName
        mainClass.set(main)
        defaultJvmOpts = listOf("--enable-native-access=ALL-UNNAMED", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
        outputDir = layout.buildDirectory.dir("scripts-$scriptName").get().asFile
        classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    }
    tasks.named<Sync>("installDist") { into("bin") { from(t) { fileMode = 493 } } }
}

tasks.test { useJUnitPlatform() }

// JavaExec starts in app/; all entry points need the same absolute runtime skill root.
tasks.withType<JavaExec>().configureEach {
    systemProperty("jclaw.skills", providers.environmentVariable("JCLAW_SKILLS_ROOT")
        .orElse(rootProject.layout.projectDirectory.dir("skills").asFile.absolutePath).get())
}
