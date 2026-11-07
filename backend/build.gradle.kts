dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        // The IDE's built-in MCP server: we contribute the floorplan_* tools the agent uses to design the map.
        bundledPlugin("com.intellij.mcpServer")
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
