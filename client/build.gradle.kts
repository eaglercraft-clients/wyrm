dependencies {
    api(projects.viaversionCommon)
    compileOnly(rootProject.libs.netty)

    testImplementation(rootProject.libs.netty)
    testImplementation(rootProject.libs.guava)
    testImplementation(rootProject.libs.snakeYaml)
    testImplementation(rootProject.libs.bundles.junit)
    testRuntimeOnly(rootProject.libs.platformLauncher)
}
