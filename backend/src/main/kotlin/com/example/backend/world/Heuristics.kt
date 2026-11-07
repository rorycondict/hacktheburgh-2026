package com.example.backend.world

import com.example.model.FurnitureKind

/** Cheap summaries used when no LLM is configured (and as placeholders while it runs). */
object Heuristics {
    private val themes = listOf(
        listOf("ui", "view", "toolwindow", "widget", "component", "render", "frontend", "screen") to "UI",
        listOf("rpc", "api", "client", "server", "http", "transport", "socket") to "networking/API",
        listOf("model", "dto", "entity", "schema") to "data model",
        listOf("test", "spec") to "tests",
        listOf("build", "gradle") to "build logic",
        listOf("doc", "readme") to "documentation",
        listOf("asset", "sprite", "image", "icon") to "assets",
        listOf("util", "helper", "common") to "utilities",
    )

    fun roomSummary(roomPath: String, files: List<FileInfo>): String {
        if (files.isEmpty()) return if (roomPath.isEmpty()) "Entrance hall; commands run at the server rack" else "Empty"
        val byLang = files.groupBy { it.language.ifBlank { "other" } }.mapValues { it.value.size }
        val (topLang, topCount) = byLang.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).toPair()
        val loc = files.sumOf { it.loc }
        val haystack = files.joinToString(" ") { it.path.lowercase() }
        val theme = themes
            .map { (keys, label) -> label to keys.sumOf { k -> Regex("[/._-]$k").findAll(haystack).count() } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
        val head = if (topCount == files.size) "${files.size} $topLang file${s(files.size)}"
        else "${files.size} files (mostly $topLang)"
        val parts = mutableListOf(head)
        if (theme != null) parts += "mostly $theme"
        parts += "$loc lines"
        val prefix = if (roomPath.isEmpty()) "Project root: " else ""
        return prefix + parts.joinToString(", ")
    }

    fun fileSummary(f: FileInfo): String {
        val what = when (FileKinds.kind(f.path)) {
            FurnitureKind.DESK -> "${f.language.ifBlank { "Source" }} source"
            FurnitureKind.BOOKSHELF -> "${f.language.ifBlank { "Text" }} document"
            FurnitureKind.CABINET -> "${f.language.ifBlank { "Config" }} config/build file"
            FurnitureKind.TEST_BENCH -> "${f.language.ifBlank { "" }} tests".trim()
            FurnitureKind.PLANT -> f.language.ifBlank { "Asset" }
            FurnitureKind.SERVER -> "Server"
        }
        val churn = if (f.churn > 0) ", ${f.churn} commit${s(f.churn)} in 30 days" else ""
        return "$what, ${f.loc} line${s(f.loc)}$churn"
    }

    private fun s(n: Int) = if (n == 1) "" else "s"
}
