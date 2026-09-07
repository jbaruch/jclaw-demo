plugins { application }

dependencies {
    implementation(libs.mcp.server)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
}

// Two servers live here; `run` defaults to the calendar one.
application { mainClass.set("jclaw.mocks.CalendarMcpKt") }

val calendarJar by tasks.registering(Jar::class) {
    archiveBaseName.set("calendar-mcp")
    archiveVersion.set("")   // stable filename: the runbook and the LC4J brief name calendar-mcp.jar
    manifest { attributes["Main-Class"] = "jclaw.mocks.CalendarMcpKt" }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val organizerJar by tasks.registering(Jar::class) {
    archiveBaseName.set("organizer-mcp")
    archiveVersion.set("")
    manifest { attributes["Main-Class"] = "jclaw.mocks.OrganizerMcpKt" }
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register("mcpJars") { dependsOn(calendarJar, organizerJar) }
