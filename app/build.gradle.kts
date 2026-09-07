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

application { mainClass.set("jclaw.MainKt") }

/** Three-pane TUI front end over the same pipeline. */
tasks.register<JavaExec>("runTui") {
    group = "application"
    dependsOn(":mocks:mcpJars")
    mainClass.set("jclaw.TuiKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    standardInput = System.`in`
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

/** The Agent Skills flourish. */
tasks.register<JavaExec>("runSkills") {
    group = "application"
    mainClass.set("jclaw.SkillsKt")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("jclaw.skills", rootProject.layout.projectDirectory.dir("skills").asFile.absolutePath)
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    standardInput = System.`in`
}
