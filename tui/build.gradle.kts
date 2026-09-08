dependencies {
    api(libs.tamboui.toolkit)
    api(libs.tamboui.jline3)
    implementation("dev.tamboui:tamboui-markdown:${libs.versions.tamboui.get()}")
    implementation(libs.kotlinx.coroutines)
}
