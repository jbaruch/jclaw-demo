plugins { application }

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.koog.llms.all)
    implementation(libs.koog.google)
    implementation(libs.kotlinx.coroutines)
}

application { mainClass.set("jclaw.MainKt") }

tasks.named<JavaExec>("run") { standardInput = System.`in` }
