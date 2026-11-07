package com.example.backend.world

import com.example.model.FurnitureKind

/** One indexed project file. [path] is project-relative with '/' separators. */
data class FileInfo(val path: String, val loc: Int, val language: String, val churn: Int, val bytes: Long = 0)

object FileKinds {
    private val languages = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin Script", "java" to "Java", "groovy" to "Groovy", "scala" to "Scala",
        "py" to "Python", "js" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript", "ts" to "TypeScript",
        "tsx" to "TypeScript", "jsx" to "JavaScript", "go" to "Go", "rs" to "Rust", "c" to "C", "h" to "C",
        "cpp" to "C++", "cc" to "C++", "hpp" to "C++", "cs" to "C#", "swift" to "Swift", "rb" to "Ruby",
        "php" to "PHP", "dart" to "Dart", "lua" to "Lua", "sh" to "Shell", "bash" to "Shell", "ps1" to "PowerShell",
        "bat" to "Batch", "cmd" to "Batch", "sql" to "SQL", "html" to "HTML", "css" to "CSS", "scss" to "SCSS",
        "vue" to "Vue", "svelte" to "Svelte", "md" to "Markdown", "txt" to "Text", "adoc" to "AsciiDoc",
        "rst" to "reStructuredText", "json" to "JSON", "jsonl" to "JSON Lines", "yaml" to "YAML", "yml" to "YAML",
        "toml" to "TOML", "xml" to "XML", "properties" to "Properties", "gradle" to "Gradle", "svg" to "SVG",
        "png" to "Image", "jpg" to "Image", "jpeg" to "Image", "gif" to "Image", "webp" to "Image", "ico" to "Image",
        "pxl" to "Pixel art", "csv" to "CSV", "ini" to "INI", "cfg" to "Config", "env" to "Env",
    )

    private val textExtensions = languages.keys - setOf("png", "jpg", "jpeg", "gif", "webp", "ico") +
        setOf("gitignore", "editorconfig", "lock", "conf", "tf", "proto", "graphql", "gql", "mdx")

    private val sourceLanguages = setOf(
        "Kotlin", "Java", "Groovy", "Scala", "Python", "JavaScript", "TypeScript", "Go", "Rust", "C", "C++", "C#",
        "Swift", "Ruby", "PHP", "Dart", "Lua", "Shell", "PowerShell", "Batch", "SQL", "HTML", "CSS", "SCSS", "Vue", "Svelte",
    )

    fun extension(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun language(path: String): String {
        val name = path.substringAfterLast('/')
        if (name == "Dockerfile") return "Docker"
        if (name == "gradlew") return "Shell"
        return languages[extension(path)] ?: ""
    }

    fun isProbablyText(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return extension(path) in textExtensions || !name.contains('.') || name.startsWith(".")
    }

    fun isTest(path: String): Boolean {
        val p = "/" + path
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        return p.contains("/test/") || p.contains("/tests/") || p.contains("/testData/") ||
            stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("Spec") ||
            stem.endsWith("_test") || stem.startsWith("test_") || stem.endsWith(".test") || stem.endsWith(".spec")
    }

    fun kind(path: String): FurnitureKind {
        val name = path.substringAfterLast('/').lowercase()
        val ext = extension(path)
        return when {
            isTest(path) && language(path) in sourceLanguages + "Kotlin Script" -> FurnitureKind.TEST_BENCH
            ext in setOf("md", "txt", "adoc", "rst", "mdx") -> FurnitureKind.BOOKSHELF
            name.endsWith(".gradle.kts") || ext in setOf("kts", "gradle", "json", "yaml", "yml", "toml", "xml", "properties", "ini", "cfg", "conf", "lock") ||
                name == "gradlew" || name.startsWith(".") || name == "dockerfile" || name == "makefile" -> FurnitureKind.CABINET
            isTest(path) -> FurnitureKind.TEST_BENCH
            language(path) in sourceLanguages -> FurnitureKind.DESK
            else -> FurnitureKind.PLANT
        }
    }
}
