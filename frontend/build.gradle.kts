dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")

        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }

    implementation(project(":shared"))
}

// Sprite atlas: compiled from assets/sprites by tools/sprite-compiler and committed under assets/generated.
// The build only copies it (it never runs the compiler). Loaded at runtime from /floorplan/atlas.{png,json}.
tasks.processResources {
    from(rootProject.file("assets/generated")) {
        include("atlas.png", "atlas.json")
        into("floorplan")
    }
}
