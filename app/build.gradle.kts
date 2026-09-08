plugins { application }

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.koog.mcp)
    implementation(libs.koog.memory)
    implementation(libs.koog.rag.vector)
    implementation(libs.koog.embeddings)
    implementation(libs.kotlinx.coroutines)
}

application { mainClass.set("jclaw.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn(":mocks:mcpJars")
    systemProperty("jclaw.mocks", rootProject.layout.projectDirectory.dir("mocks/build/libs").asFile.absolutePath)
    systemProperty("jclaw.memory", rootProject.layout.projectDirectory.dir("memory").asFile.absolutePath)
    standardInput = System.`in`
}
