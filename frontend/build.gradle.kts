dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")

        compileOnly(libs.kotlin.serialization.core.jvm)
        compileOnly(libs.kotlin.serialization.json.jvm)
    }

    implementation(project(":shared"))
}

// Sprites: one PNG per sprite in assets/ (flat), named as the renderer refers to them (e.g. obj_desk.png).
// Copied unchanged and loaded at runtime from /floorplan/sprites/<name>.png (see render/Sprites.kt).
tasks.processResources {
    from(rootProject.file("assets")) {
        include("*.png")
        into("floorplan/sprites")
    }
}
