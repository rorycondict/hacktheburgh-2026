dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
    }

    implementation(project(":shared"))

    // Bundled with the IDE; do not ship our own copy.
    compileOnly(libs.kotlin.serialization.core.jvm)
    compileOnly(libs.kotlin.serialization.json.jvm)

    // Plain JUnit 5 tests for the pure logic (mapper, reducer, layout).
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.serialization.core.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
}

tasks.test {
    useJUnitPlatform()
}
