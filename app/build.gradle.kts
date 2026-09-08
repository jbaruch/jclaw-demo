plugins { application }

dependencies {
    testImplementation("io.kotest:kotest-runner-junit5:6.0.3")
    testImplementation("io.kotest:kotest-assertions-core:6.0.3")
    implementation(libs.koog.skills)
    implementation(libs.koog.agents.ext)
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

/** Standalone runtime skill runner, also available through normal chat. */
tasks.register<JavaExec>("runSkills") {
    group = "application"
    mainClass.set("jclaw.SkillsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
}
val skillsScripts = tasks.register<CreateStartScripts>("startScripts_app-skills") {
    applicationName = "app-skills"
    mainClass.set("jclaw.SkillsKt")
    defaultJvmOpts = application.applicationDefaultJvmArgs
    outputDir = layout.buildDirectory.dir("scripts-app-skills").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}
tasks.named<Sync>("installDist") { into("bin") { from(skillsScripts) { fileMode = 493 } } }
tasks.test { useJUnitPlatform() }

// JavaExec starts in app/; all entry points need the same absolute runtime skill root.
tasks.withType<JavaExec>().configureEach {
    systemProperty("jclaw.skills", providers.environmentVariable("JCLAW_SKILLS_ROOT")
        .orElse(rootProject.layout.projectDirectory.dir("skills").asFile.absolutePath).get())
}
