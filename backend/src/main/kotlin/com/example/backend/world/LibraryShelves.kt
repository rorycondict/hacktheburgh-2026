package com.example.backend.world

import com.example.model.LibraryDto

/**
 * How the dependency library's books (one per library) go into its bookcases, and the captions and summaries
 * for them. Books are sorted by group then name, like a real library, and a group stays in one case when it fits.
 */
object LibraryShelves {
    class Shelf(val label: String, val libraries: List<LibraryDto>)

    /** Fewest books a case gets before another case is started (so a small project still fills a shelf or two). */
    private const val MIN_PER_CASE = 8

    fun key(l: LibraryDto): String = l.group.ifEmpty { l.name }

    /**
     * At most [max] cases of at most [cap] books. If there are more than [max] x [cap] libraries the last case
     * holds the overflow (drawn full; its tooltip lists them all).
     */
    fun shelve(libraries: List<LibraryDto>, max: Int, cap: Int = LibraryDto.BOOKS_PER_CASE): List<Shelf> {
        if (libraries.isEmpty() || max <= 0) return emptyList()
        val sorted = libraries.sortedWith(compareBy<LibraryDto>({ key(it).lowercase() }, { it.name.lowercase() }))
        val per = ((sorted.size + max - 1) / max).coerceIn(MIN_PER_CASE, cap)
        var cases = pack(sorted, per, keepGroups = true)
        if (cases.size > max) cases = pack(sorted, cap, keepGroups = false)
        if (cases.size > max) cases = cases.take(max - 1) + listOf(cases.drop(max - 1).flatten())
        return cases.map { Shelf(label(it), it) }
    }

    /** Fills cases of [per] books in order; with [keepGroups] a group that would straddle two cases starts a new one. */
    private fun pack(sorted: List<LibraryDto>, per: Int, keepGroups: Boolean): List<List<LibraryDto>> {
        val out = ArrayList<MutableList<LibraryDto>>()
        val groups = if (keepGroups) sorted.groupBy { key(it) }.values.toList() else sorted.map { listOf(it) }
        for (g in groups) {
            for (chunk in g.chunked(per)) {
                val cur = out.lastOrNull()
                if (cur != null && cur.size + chunk.size <= per) cur += chunk else out += chunk.toMutableList()
            }
        }
        return out
    }

    /** "Bookcase: org.jetbrains.kotlinx" for one group, else the range like a library aisle: "Bookcase: androidx – com.squareup". */
    private fun label(libs: List<LibraryDto>): String {
        val keys = libs.map { key(it) }.distinct()
        return if (keys.size == 1) "Bookcase: ${keys[0]}" else "Bookcase: ${short(keys.first())} – ${short(keys.last())}"
    }

    private fun short(k: String): String = if (k.length <= 18) k else k.take(17) + "…"

    /** "kotlinx-serialization-json 1.9.0 (main), kotlin-test (test)". */
    fun summary(libs: List<LibraryDto>): String {
        val listed = libs.take(6).joinToString(", ") { l ->
            l.coordinate + (if (l.version.isNotBlank()) " " + l.version else "") + " (" + l.scope + ")"
        }
        val more = if (libs.size > 6) ", and ${libs.size - 6} more" else ""
        val where = libs.map { it.declaredIn }.filter { it.isNotBlank() }.distinct()
        val declared = if (where.isEmpty()) "" else "; declared in " + where.take(3).joinToString(", ")
        return listed + more + declared
    }

    /** "14 third-party libraries (12 Maven, 2 npm): 10 main, 4 test". */
    fun roomSummary(libs: List<LibraryDto>): String {
        val eco = libs.groupingBy { it.ecosystem }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
        val scopes = libs.groupingBy { it.scope }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" }
        val n = libs.size
        return "$n third-party librar${if (n == 1) "y" else "ies"} ($eco): $scopes"
    }
}
