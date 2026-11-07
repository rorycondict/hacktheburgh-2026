package com.example.backend.world

import java.nio.file.Files
import java.nio.file.Path

/**
 * Cheap, language-agnostic "who refers to whom": a source file depends on another when it mentions
 * that file's name stem (usually its main class) as an identifier. Good enough to put related rooms
 * next to each other; not a real call graph.
 */
object DependencyScanner {
    private const val MAX_FILES = 3_000
    private const val MAX_BYTES = 256 * 1024
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]{3,}")
    private val GENERIC = setOf("main", "index", "utils", "util", "test", "build", "settings", "readme", "types", "init", "__init__", "plugin", "module")

    fun scan(root: Path, files: List<FileInfo>): Map<String, Set<String>> {
        val sources = files.filter { FileKinds.kind(it.path).let { k -> k == com.example.model.FurnitureKind.DESK || k == com.example.model.FurnitureKind.TEST_BENCH } }
            .sortedByDescending { it.loc }.take(MAX_FILES)
        // Stem -> files with that stem; ambiguous stems (more than 2 files) are ignored.
        val byStem = sources.groupBy { it.path.substringAfterLast('/').substringBefore('.') }
            .filter { (stem, fs) -> stem.length >= 4 && stem.lowercase() !in GENERIC && fs.size <= 2 }
        if (byStem.isEmpty()) return emptyMap()
        val out = HashMap<String, Set<String>>()
        for (f in sources) {
            val text = try {
                val p = root.resolve(f.path)
                if (Files.size(p) > MAX_BYTES) continue
                Files.readString(p)
            } catch (_: Exception) {
                continue
            }
            val own = f.path.substringAfterLast('/').substringBefore('.')
            val refs = HashSet<String>()
            for (m in IDENT.findAll(text)) {
                val id = m.value
                if (id == own) continue
                byStem[id]?.forEach { if (it.path != f.path) refs += it.path }
            }
            if (refs.isNotEmpty()) out[f.path] = refs
        }
        return out
    }
}
