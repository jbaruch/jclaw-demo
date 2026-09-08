plugins { application }

dependencies {
    implementation(project(":tui"))
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.kotlinx.coroutines)
}

application { mainClass.set("jclaw.MainKt") }

tasks.named<JavaExec>("run") { standardInput = System.`in` }

/** The three-pane TUI front end over the same agent: `gradle :app:runTui`. */
tasks.register<JavaExec>("runTui") {
    group = "application"
    mainClass.set("jclaw.TuiKt")
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
}

// The application plugin scripts only mainClass. On stage the TUI must run without
// Gradle in the loop, so it gets its own launcher next to `app`.
val tuiScripts = tasks.register<CreateStartScripts>("startScripts_app-tui") {
    applicationName = "app-tui"
    mainClass.set("jclaw.TuiKt")
    outputDir = layout.buildDirectory.dir("scripts-app-tui").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
}
tasks.named<Sync>("installDist") { into("bin") { from(tuiScripts) { fileMode = 493 } } }
