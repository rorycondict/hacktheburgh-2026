package com.example.backend.session

/** Path helpers shared by routing, the reducer and the RPC layer. Separators are normalised to '/'. */
object PathUtil {
    val caseInsensitive: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    fun normalise(path: String): String {
        var p = path.trim().replace('\\', '/')
        if (p.startsWith("file://")) p = p.removePrefix("file://").let { if (it.length > 2 && it[0] == '/' && it[2] == ':') it.drop(1) else it }
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith('/')) p = p.dropLast(1)
        return p
    }

    fun isAbsolute(path: String): Boolean {
        val p = normalise(path)
        return p.startsWith('/') || (p.length >= 2 && p[1] == ':')
    }

    /** True if [path] equals [root] or lies under it. */
    fun isUnder(path: String, root: String, ignoreCase: Boolean = caseInsensitive): Boolean {
        val p = normalise(path)
        val r = normalise(root)
        if (r.isEmpty()) return false
        return p.equals(r, ignoreCase) || p.startsWith("$r/", ignoreCase) || (r.endsWith('/') && p.startsWith(r, ignoreCase))
    }

    /**
     * Project-relative path if [path] is under one of [roots] (first match wins), otherwise the
     * normalised absolute path. Relative input stays relative.
     */
    fun relativise(path: String, roots: List<String>, ignoreCase: Boolean = caseInsensitive): String {
        val p = normalise(path)
        if (!isAbsolute(p)) return p.removePrefix("./")
        for (root in roots) {
            val r = normalise(root)
            if (r.isEmpty()) continue
            if (p.equals(r, ignoreCase)) return ""
            if (p.startsWith("$r/", ignoreCase)) return p.substring(r.length + 1)
        }
        return p
    }
}
