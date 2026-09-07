plugins { application }

dependencies {
    implementation(project(":domain"))
    implementation(project(":tui"))
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.koog.mcp)
    implementation(libs.koog.memory)
    implementation(libs.koog.agents.cli)
    implementation(libs.koog.skills)
    implementation(libs.koog.agents.ext)
    implementation(libs.kotlinx.coroutines)
}

application { mainClass.set("jclaw.round4.MainKt") }

/** The three-pane TUI build: `gradle :round4-pipeline:runTui`. */
tasks.register<JavaExec>("runTui") {
    group = "application"
    dependsOn(":mocks:mcpJars")
    mainClass.set("jclaw.round4.TuiKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    standardInput = System.`in`
}

/** The Agent Skills flourish: `gradle :round4-pipeline:runSkills`. */
tasks.register<JavaExec>("runSkills") {
    group = "application"
    mainClass.set("jclaw.round4.SkillsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.skills", rootProject.layout.projectDirectory.dir("skills").asFile.absolutePath)
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    standardInput = System.`in`
}
