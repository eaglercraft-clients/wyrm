dependencies {
    // Provided by the universal jar, whose gson and fastutil are relocated; this module is relocated the same way
    compileOnlyApi(projects.viaversionCommon)
    compileOnly(rootProject.libs.netty)

    testImplementation(projects.viaversionCommon)
    testImplementation(rootProject.libs.netty)
    testImplementation(rootProject.libs.guava)
    testImplementation(rootProject.libs.snakeYaml)
    testImplementation(rootProject.libs.bundles.junit)
    testRuntimeOnly(rootProject.libs.platformLauncher)
}
