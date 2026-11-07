package com.example.backend.world

import com.example.model.LibraryDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the third-party libraries a project declares, by reading its build and package manifests:
 * - JVM: Gradle scripts and version catalogs, Maven `pom.xml`, sbt `build.sbt`
 * - JS / PHP / Ruby / Dart / Elixir: `package.json`, `composer.json`, `Gemfile` and `*.gemspec`, `pubspec.yaml`, `mix.exs`
 * - Python: `requirements*.txt`, `pyproject.toml` (PEP 621 and Poetry), `Pipfile`, `setup.py`
 * - Rust / Go / Swift: `Cargo.toml`, `go.mod`, `Package.swift`
 * - .NET: `*.csproj` / `*.fsproj` / `*.vbproj`, `Directory.Packages.props`, `packages.config`
 * - C / C++: `CMakeLists.txt` and `*.cmake` (find_package, FetchContent, CPM, pkg_check_modules, ExternalProject),
 *   `vcpkg.json`, `conanfile.txt` / `conanfile.py`, `meson.build`
 * - git submodules (`.gitmodules`) and vendored copies in `third_party/`, `external/`, `vendor/`... directories.
 *
 * Only direct declarations are reported (not the transitive closure), which is what a reader wants to see.
 * Manifests inside vendored directories and submodules belong to those libraries and are skipped. Pure apart
 * from reading the files; every parse failure just yields fewer libraries.
 */
object LibraryScanner {
    private const val MAX_BYTES = 512 * 1024
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Stronger scopes win when one library is declared several times. */
    private val SCOPE_RANK = listOf("main", "compile-only", "runtime", "annotation", "peer", "optional", "dev", "test", "build", "catalog", "indirect")

    fun scan(root: Path, files: List<FileInfo>): List<LibraryDto> {
        fun read(path: String): String? = try {
            val p = root.resolve(path)
            if (Files.size(p) > MAX_BYTES) null else Files.readString(p)
        } catch (_: Exception) {
            null
        }
        val all = files.map { it.path }
        val submodules = if (".gitmodules" in all) read(".gitmodules")?.let { gitSubmodules(it, ".gitmodules") }.orEmpty() else emptyList()
        val vendored = vendored(all, submodules.map { it.second }.toSet(), isGo = "go.mod" in all)
        val vendorRoots = vendored.map { it.first + "/" } + submodules.map { it.second + "/" }
        val paths = all.filter { p -> vendorRoots.none { p.startsWith(it) } }
        val catalogs = paths.filter { it.endsWith(".versions.toml") }
            .mapNotNull { p -> read(p)?.let { p to parseCatalog(it, p) } }
        val out = ArrayList<LibraryDto>()
        for ((p, cat) in catalogs) read(p)?.let { text -> out += withLines(cat.libraries.values.map { it.copy(declaredIn = p) }, text) }
        out += submodules.map { it.first }
        out += vendored.map { it.second }
        for (p in paths) {
            if (!isManifest(p)) continue
            val text = read(p) ?: continue
            val found = parse(p, text, catalogs)
            // Catalog hits point at the catalog, whose lines are already set.
            out += found.filter { it.declaredIn != p } + withLines(found.filter { it.declaredIn == p }, text)
        }
        return merge(out)
    }

    private val MANIFEST_NAMES = setOf(
        "pom.xml", "package.json", "pyproject.toml", "Cargo.toml", "go.mod", "CMakeLists.txt", "vcpkg.json",
        "conanfile.txt", "conanfile.py", "meson.build", "Gemfile", "composer.json", "Directory.Packages.props",
        "packages.config", "pubspec.yaml", "Package.swift", "Pipfile", "setup.py", "build.sbt", "mix.exs",
    )

    fun isManifest(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name in MANIFEST_NAMES || name.endsWith(".gradle.kts") || name.endsWith(".gradle") || name.endsWith(".cmake") ||
            name.endsWith(".csproj") || name.endsWith(".fsproj") || name.endsWith(".vbproj") || name.endsWith(".gemspec") ||
            (name.startsWith("requirements") && name.endsWith(".txt"))
    }

    /** Libraries declared in one manifest (chosen by file name); empty for files that are not manifests. */
    fun parse(path: String, text: String, catalogs: List<Pair<String, Catalog>> = emptyList()): List<LibraryDto> {
        val name = path.substringAfterLast('/')
        return when {
            name.endsWith(".gradle.kts") || name.endsWith(".gradle") -> gradle(text, path, catalogs)
            name == "pom.xml" -> maven(text, path)
            name == "build.sbt" -> sbt(text, path)
            name == "package.json" -> npm(text, path)
            name.startsWith("requirements") && name.endsWith(".txt") -> requirements(text, path)
            name == "pyproject.toml" -> pyproject(text, path)
            name == "Pipfile" -> pipfile(text, path)
            name == "setup.py" -> setupPy(text, path)
            name == "Cargo.toml" -> cargo(text, path)
            name == "go.mod" -> goMod(text, path)
            name == "CMakeLists.txt" || name.endsWith(".cmake") -> cmake(text, path)
            name == "vcpkg.json" -> vcpkg(text, path)
            name == "conanfile.txt" || name == "conanfile.py" -> conan(text, path)
            name == "meson.build" -> meson(text, path)
            name == "Gemfile" || name.endsWith(".gemspec") -> ruby(text, path)
            name == "composer.json" -> composer(text, path)
            name.endsWith(".csproj") || name.endsWith(".fsproj") || name.endsWith(".vbproj") ||
                name == "Directory.Packages.props" || name == "packages.config" -> nuget(text, path)
            name == "pubspec.yaml" -> pubspec(text, path)
            name == "Package.swift" -> swift(text, path)
            name == "mix.exs" -> mix(text, path)
            else -> emptyList()
        }
    }

    /** Sets [LibraryDto.line] to the first line of [text] that mentions the library, where not already known. */
    fun withLines(libs: List<LibraryDto>, text: String): List<LibraryDto> {
        if (libs.isEmpty()) return libs
        val lines = text.lines()
        return libs.map { l ->
            if (l.line > 0) return@map l
            val needles = listOf(l.name, l.name.substringAfterLast('/'), l.group).filter { it.length >= 2 }
            val i = needles.firstNotNullOfOrNull { n -> lines.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } } ?: -1
            if (i >= 0) l.copy(line = i + 1) else l
        }
    }

    /**
     * Parses IntelliJ project-model library names ("Gradle: org.jetbrains:annotations:24.0.0",
     * "Maven: g:a:v", or a plain name). Used when the project has no manifest we understand.
     */
    fun fromModelNames(names: Collection<String>): List<LibraryDto> = merge(names.mapNotNull { raw ->
        val n = raw.substringAfter(": ", raw).trim()
        if (n.isEmpty()) return@mapNotNull null
        val parts = n.split(':')
        when {
            parts.size >= 3 -> LibraryDto(parts[1], parts[0], parts.last(), "IDE", "main", "")
            parts.size == 2 -> LibraryDto(parts[1], parts[0], "", "IDE", "main", "")
            else -> LibraryDto(n, "", "", "IDE", "main", "")
        }
    })

    /** One entry per (ecosystem, group, name): keeps a known version, the strongest scope and the first manifest. */
    fun merge(libs: List<LibraryDto>): List<LibraryDto> =
        libs.filter { it.name.isNotBlank() }
            .groupBy { Triple(it.ecosystem, it.group, it.name) }
            .map { (_, same) ->
                val located = same.firstOrNull { it.declaredIn.isNotBlank() } ?: same.first()
                located.copy(
                    version = same.firstNotNullOfOrNull { it.version.ifBlank { null } } ?: "",
                    scope = same.minBy { rank(it.scope) }.scope,
                )
            }
            .sortedWith(compareBy({ it.ecosystem }, { it.group }, { it.name }))

    private fun rank(scope: String): Int = SCOPE_RANK.indexOf(scope).let { if (it < 0) SCOPE_RANK.size else it }

    // ---- Gradle ------------------------------------------------------------------------------

    /** A version catalog: accessor ("kotlin.serialization.core.jvm") -> library. */
    class Catalog(val name: String, val libraries: Map<String, LibraryDto>)

    fun parseCatalog(text: String, fileName: String = "libs.versions.toml"): Catalog {
        val sections = tomlSections(text)
        val versions = sections["versions"].orEmpty().mapNotNull { line ->
            val (k, v) = keyValue(line) ?: return@mapNotNull null
            k to (unquote(v) ?: inlineTable(v)["strictly"] ?: inlineTable(v)["require"] ?: inlineTable(v)["prefer"] ?: "")
        }.toMap()
        val libs = LinkedHashMap<String, LibraryDto>()
        for (line in sections["libraries"].orEmpty()) {
            val (alias, v) = keyValue(line) ?: continue
            val accessor = alias.replace('-', '.').replace('_', '.')
            val lib = unquote(v)?.let { coord ->
                val p = coord.split(':')
                if (p.size < 2) null else LibraryDto(p[1], p[0], p.getOrElse(2) { "" }, "Maven", "catalog", "")
            } ?: inlineTable(v).let { t ->
                val module = t["module"]
                val group = module?.substringBefore(':') ?: t["group"] ?: return@let null
                val name = module?.substringAfter(':', "") ?: t["name"] ?: return@let null
                val version = t["version"] ?: t["version.ref"]?.let { versions[it] } ?: ""
                LibraryDto(name, group, version, "Maven", "catalog", "")
            } ?: continue
            libs[accessor] = lib
        }
        return Catalog(fileName.substringAfterLast('/').substringBefore('.'), libs)
    }

    private const val CONF = "(\\w*(?:[iI]mplementation|[aA]pi|[cC]ompileOnly|[rR]untimeOnly|[aA]nnotationProcessor)|kapt\\w*|ksp\\w*|classpath|compile|testCompile|runtime)"
    private val GRADLE_COORD = Regex(CONF + "\\s*\\(?\\s*(?:(?:enforcedPlatform|platform)\\s*\\(\\s*)?[\"']([\\w.\\-]+):([\\w.\\-]+)(?::([^\"'\\s)]+))?[\"']")
    private val GRADLE_MAP = Regex(CONF + "\\s*\\(?\\s*group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?")
    private val GRADLE_KOTLIN = Regex(CONF + "\\s*\\(?\\s*kotlin\\s*\\(\\s*\"([\\w.\\-]+)\"(?:\\s*,\\s*\"([^\"]+)\")?")
    private val GRADLE_ALIAS = Regex(CONF + "\\s*\\(?\\s*(?:(?:enforcedPlatform|platform)\\s*\\(\\s*)?(\\w+)\\.([\\w.]+)")

    fun gradle(text: String, path: String, catalogs: List<Pair<String, Catalog>> = emptyList()): List<LibraryDto> {
        val code = stripComments(text)
        val out = ArrayList<LibraryDto>()
        for (m in GRADLE_COORD.findAll(code) + GRADLE_MAP.findAll(code)) {
            val (conf, group, name, version) = m.destructured
            out += LibraryDto(name, group, version, "Maven", gradleScope(conf), path)
        }
        for (m in GRADLE_KOTLIN.findAll(code)) {
            val (conf, module, version) = m.destructured
            out += LibraryDto("kotlin-$module", "org.jetbrains.kotlin", version, "Maven", gradleScope(conf), path)
        }
        for (m in GRADLE_ALIAS.findAll(code)) {
            val (conf, catalogName, accessorRaw) = m.destructured
            val accessor = accessorRaw.removeSuffix(".get")
            if (accessor.startsWith("versions.") || accessor.startsWith("plugins.") || accessor.startsWith("bundles.")) continue
            val hit = catalogs.firstNotNullOfOrNull { (p, c) -> if (c.name == catalogName) c.libraries[accessor]?.copy(declaredIn = p) else null } ?: continue
            out += hit.copy(scope = gradleScope(conf))
        }
        return out
    }

    private fun gradleScope(conf: String): String {
        val c = conf.lowercase()
        return when {
            "test" in c -> "test"
            c == "classpath" -> "build"
            c.startsWith("kapt") || c.startsWith("ksp") || c.endsWith("annotationprocessor") -> "annotation"
            c.endsWith("compileonly") -> "compile-only"
            c.endsWith("runtimeonly") || c == "runtime" -> "runtime"
            else -> "main"
        }
    }

    // ---- Maven / sbt -------------------------------------------------------------------------

    private val DEPENDENCY = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
    private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun maven(text: String, path: String): List<LibraryDto> {
        val xml = XML_COMMENT.replace(text, "")
        val props = Regex("<properties>(.*?)</properties>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.let { block -> Regex("<([\\w.\\-]+)>([^<]*)</\\1>").findAll(block).associate { it.groupValues[1] to it.groupValues[2].trim() } }
            .orEmpty()
        fun resolve(v: String): String = Regex("\\$\\{([^}]+)}").replace(v) { props[it.groupValues[1]] ?: it.value }
        fun tag(block: String, t: String) = Regex("<$t>\\s*([^<]*?)\\s*</$t>").find(block)?.groupValues?.get(1)?.let(::resolve) ?: ""
        return DEPENDENCY.findAll(xml).mapNotNull { m ->
            val b = m.groupValues[1]
            val group = tag(b, "groupId"); val name = tag(b, "artifactId")
            if (group.isEmpty() || name.isEmpty()) return@mapNotNull null
            val scope = when (tag(b, "scope")) {
                "test" -> "test"; "provided" -> "compile-only"; "runtime" -> "runtime"; "import" -> "build"; else -> "main"
            }
            LibraryDto(name, group, tag(b, "version").takeUnless { it.startsWith("\${") } ?: "", "Maven", scope, path)
        }.toList()
    }

    private val SBT = Regex("\"([\\w.\\-]+)\"\\s*%{1,3}\\s*\"([\\w.\\-]+)\"\\s*%\\s*\"([^\"]+)\"(?:\\s*%\\s*\"?(\\w+)\"?)?")

    fun sbt(text: String, path: String): List<LibraryDto> = SBT.findAll(stripComments(text)).map { m ->
        val scope = when (m.groupValues[4].lowercase()) { "test", "it" -> "test"; "provided" -> "compile-only"; else -> "main" }
        LibraryDto(m.groupValues[2], m.groupValues[1], m.groupValues[3], "Maven", scope, path)
    }.toList()

    // ---- npm / Composer ------------------------------------------------------------------------

    fun npm(text: String, path: String): List<LibraryDto> {
        val root = jsonObject(text) ?: return emptyList()
        val sections = listOf("dependencies" to "main", "devDependencies" to "dev", "peerDependencies" to "peer", "optionalDependencies" to "optional")
        return sections.flatMap { (key, scope) ->
            (root[key] as? JsonObject).orEmpty().map { (name, v) ->
                val group = if (name.startsWith("@")) name.substringBefore('/') else ""
                LibraryDto(name, group, (v as? JsonPrimitive)?.contentOrNull.orEmpty(), "npm", scope, path)
            }
        }
    }

    fun composer(text: String, path: String): List<LibraryDto> {
        val root = jsonObject(text) ?: return emptyList()
        return listOf("require" to "main", "require-dev" to "dev").flatMap { (key, scope) ->
            (root[key] as? JsonObject).orEmpty().mapNotNull { (name, v) ->
                if (name == "php" || name.startsWith("ext-") || name.startsWith("lib-")) return@mapNotNull null
                LibraryDto(name, name.substringBefore('/', ""), (v as? JsonPrimitive)?.contentOrNull.orEmpty(), "Packagist", scope, path)
            }
        }
    }

    private fun jsonObject(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }

    // ---- Python ------------------------------------------------------------------------------

    private val PEP508 = Regex("^\\s*([A-Za-z0-9][A-Za-z0-9._\\-]*)\\s*(?:\\[[^]]*])?\\s*([^;#]*)")

    private fun pep508(spec: String, scope: String, path: String): LibraryDto? {
        val m = PEP508.find(spec) ?: return null
        return LibraryDto(m.groupValues[1], "", m.groupValues[2].trim().removePrefix("=="), "PyPI", scope, path)
    }

    fun requirements(text: String, path: String): List<LibraryDto> {
        val name = path.substringAfterLast('/').lowercase()
        val scope = if ("dev" in name || "test" in name) "dev" else "main"
        return text.lineSequence().map { it.substringBefore(" #").trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('-') && "://" !in it }
            .mapNotNull { pep508(it, scope, path) }.toList()
    }

    fun pyproject(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        val sections = tomlSections(text, keepRaw = true)
        // PEP 621: [project] dependencies = [...]; [project.optional-dependencies] group = [...]
        sections["project"]?.joinToString("\n")?.let { body ->
            Regex("^\\s*dependencies\\s*=\\s*\\[(.*?)]\\s*$", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)).find(body)?.groupValues?.get(1)?.let { list ->
                quoted(list).mapNotNullTo(out) { pep508(it, "main", path) }
            }
        }
        sections["project.optional-dependencies"]?.joinToString("\n")?.let { body -> quoted(body).mapNotNullTo(out) { pep508(it, "optional", path) } }
        // Poetry: [tool.poetry.dependencies] name = "^1.0", [tool.poetry.group.<g>.dependencies]
        for ((section, lines) in sections) {
            val scope = when {
                section == "tool.poetry.dependencies" -> "main"
                section == "tool.poetry.dev-dependencies" -> "dev"
                section.startsWith("tool.poetry.group.") && section.endsWith(".dependencies") ->
                    if (section.removePrefix("tool.poetry.group.").startsWith("dev") || ".test" in section) "dev" else "optional"
                else -> continue
            }
            for (line in lines) {
                val (k, v) = keyValue(line) ?: continue
                if (k == "python") continue
                out += LibraryDto(k, "", unquote(v) ?: inlineTable(v)["version"] ?: "", "PyPI", scope, path)
            }
        }
        return out
    }

    fun pipfile(text: String, path: String): List<LibraryDto> = tomlSections(text).flatMap { (section, lines) ->
        val scope = when (section) { "packages" -> "main"; "dev-packages" -> "dev"; else -> return@flatMap emptyList() }
        lines.mapNotNull { line ->
            val (k, v) = keyValue(line) ?: return@mapNotNull null
            val version = unquote(v) ?: inlineTable(v)["version"] ?: ""
            LibraryDto(k, "", if (version == "*") "" else version, "PyPI", scope, path)
        }
    }

    fun setupPy(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        for ((key, scope) in listOf("install_requires" to "main", "tests_require" to "test")) {
            Regex(key + "\\s*=\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(text)?.let { m ->
                quoted(m.groupValues[1]).mapNotNullTo(out) { pep508(it, scope, path) }
            }
        }
        return out
    }

    // ---- Rust / Go -----------------------------------------------------------------------------

    fun cargo(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        fun scopeOf(table: String): String? = when {
            table.endsWith("dev-dependencies") -> "dev"
            table.endsWith("build-dependencies") -> "build"
            table.endsWith("dependencies") -> "main"
            else -> null
        }
        for ((section, lines) in tomlSections(text)) {
            scopeOf(section)?.let { scope ->
                for (line in lines) {
                    val (k, v) = keyValue(line) ?: continue
                    val t = inlineTable(v)
                    if (t["path"] != null && t["version"] == null) continue   // a crate of this workspace
                    out += LibraryDto(t["package"] ?: k, "", unquote(v) ?: t["version"] ?: "", "Cargo", scope, path)
                }
                continue
            }
            // [dependencies.serde] version = "1"
            val table = section.substringBeforeLast('.', "")
            val scope = scopeOf(table) ?: continue
            val kv = lines.mapNotNull(::keyValue).associate { (k, v) -> k to (unquote(v) ?: "") }
            if (kv["path"] != null && kv["version"] == null) continue
            out += LibraryDto(kv["package"]?.ifBlank { null } ?: section.substringAfterLast('.'), "", kv["version"].orEmpty(), "Cargo", scope, path)
        }
        return out
    }

    fun goMod(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        var inBlock = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val spec = when {
                line.startsWith("require (") -> { inBlock = true; continue }
                inBlock && line.startsWith(")") -> { inBlock = false; continue }
                inBlock -> line
                line.startsWith("require ") -> line.removePrefix("require ").trim()
                else -> continue
            }
            val parts = spec.substringBefore("//").trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val module = parts[0]
            val group = module.split('/').take(2).joinToString("/")
            out += LibraryDto(module, group, parts[1], "Go", if ("// indirect" in spec) "indirect" else "main", path)
        }
        return out
    }

    // ---- C / C++ -------------------------------------------------------------------------------

    /** CMake commands as (lower-case name, args). Args may span lines; nested parens are skipped over. */
    private fun cmakeCalls(text: String): List<Pair<String, List<String>>> {
        val code = text.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf('#')
            if (i >= 0 && line.substring(0, i).count { it == '"' } % 2 == 0) line.substring(0, i) else line
        }
        val out = ArrayList<Pair<String, List<String>>>()
        val head = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        val arg = Regex("\"([^\"]*)\"|([^\\s\"()]+)")
        var pos = 0
        while (true) {
            val m = head.find(code, pos) ?: break
            val start = m.range.last + 1
            var depth = 1
            var i = start
            while (i < code.length && depth > 0) {
                if (code[i] == '(') depth++ else if (code[i] == ')') depth--
                i++
            }
            val body = code.substring(start, if (depth == 0) i - 1 else i)
            out += m.groupValues[1].lowercase() to arg.findAll(body).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
            pos = i
        }
        return out
    }

    private val CMAKE_KEYWORDS = setOf("REQUIRED", "QUIET", "CONFIG", "MODULE", "NO_MODULE", "COMPONENTS", "OPTIONAL_COMPONENTS", "EXACT", "GLOBAL", "IMPORTED_TARGET", "NAMES")
    /** find_package targets that are tools or toolchain parts rather than libraries. */
    private val CMAKE_IGNORED = setOf("PkgConfig", "Git", "Python", "Python3", "Doxygen", "Threads", "CUDAToolkit", "Perl", "BISON", "FLEX")
    private val VERSIONISH = Regex("^v?\\d+(\\.\\w+)*$")

    fun cmake(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        for ((cmd, args) in cmakeCalls(text)) {
            when (cmd) {
                "find_package" -> {
                    val name = args.firstOrNull() ?: continue
                    if (name in CMAKE_IGNORED || name.startsWith("\${")) continue
                    val version = args.getOrNull(1)?.takeIf { VERSIONISH.matches(it) } ?: ""
                    out += LibraryDto(name, "", version, "CMake", "main", path)
                }
                "fetchcontent_declare", "externalproject_add" -> {
                    val name = args.firstOrNull() ?: continue
                    val repo = args.valueAfter("GIT_REPOSITORY") ?: args.valueAfter("URL") ?: ""
                    val tag = args.valueAfter("GIT_TAG") ?: args.valueAfter("URL")?.let(::versionFromUrl) ?: ""
                    out += LibraryDto(name, repoOwner(repo), shortRef(tag), "CMake", "main", path)
                }
                "cpmaddpackage", "cpmfindpackage" -> {
                    val first = args.firstOrNull() ?: continue
                    if ("NAME" in args) {
                        val name = args.valueAfter("NAME") ?: continue
                        val repo = args.valueAfter("GITHUB_REPOSITORY")?.let { "github.com/$it" } ?: args.valueAfter("GIT_REPOSITORY") ?: ""
                        out += LibraryDto(name, repoOwner(repo), shortRef(args.valueAfter("VERSION") ?: args.valueAfter("GIT_TAG") ?: ""), "CMake", "main", path)
                    } else {
                        // "gh:fmtlib/fmt#7.1.3" or "gh:nlohmann/json@3.11.2"
                        val spec = first.substringAfter(':')
                        val repo = spec.substringBefore('@').substringBefore('#')
                        val version = if ('@' in spec) spec.substringAfter('@') else spec.substringAfter('#', "")
                        out += LibraryDto(repo.substringAfterLast('/'), repo.substringBeforeLast('/', ""), shortRef(version), "CMake", "main", path)
                    }
                }
                "pkg_check_modules", "pkg_search_module" -> {
                    for (a in args.drop(1)) {
                        if (a in CMAKE_KEYWORDS) continue
                        val m = Regex("^([A-Za-z0-9_.+\\-]+?)\\s*(?:([<>=]=?)\\s*(.+))?$").find(a) ?: continue
                        out += LibraryDto(m.groupValues[1], "", m.groupValues[3], "pkg-config", "main", path)
                    }
                }
            }
        }
        return out
    }

    private fun List<String>.valueAfter(key: String): String? = indexOf(key).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    /** Commit hashes shortened to 10 chars; tags kept. */
    private fun shortRef(ref: String): String = if (ref.length == 40 && ref.all { it.isLetterOrDigit() }) ref.take(10) else ref

    /** "https://github.com/fmtlib/fmt.git" -> "github.com/fmtlib". */
    private fun repoOwner(repo: String): String {
        val r = repo.removePrefix("gh:").substringAfter("://").removeSuffix(".git").trimEnd('/')
        return if ('/' in r) r.substringBeforeLast('/') else ""
    }

    private fun versionFromUrl(url: String): String? = Regex("(?:v|-)(\\d+(?:\\.\\d+)+)").findAll(url).lastOrNull()?.groupValues?.get(1)

    fun vcpkg(text: String, path: String): List<LibraryDto> {
        val deps = jsonObject(text)?.get("dependencies") as? JsonArray ?: return emptyList()
        return deps.mapNotNull { d ->
            when (d) {
                is JsonPrimitive -> d.contentOrNull?.let { LibraryDto(it, "", "", "vcpkg", "main", path) }
                is JsonObject -> (d["name"] as? JsonPrimitive)?.contentOrNull?.let { n ->
                    val v = (d["version>="] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val host = (d["host"] as? JsonPrimitive)?.contentOrNull == "true"
                    LibraryDto(n, "", v, "vcpkg", if (host) "build" else "main", path)
                }
                else -> null
            }
        }
    }

    fun conan(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        fun ref(r: String, scope: String) {
            val s = r.trim().substringBefore('@')
            if ('/' in s) out += LibraryDto(s.substringBefore('/'), "", s.substringAfter('/'), "Conan", scope, path)
        }
        if (path.endsWith(".py")) {
            // requires = "a/1.0", "b/2.0"  |  self.requires("a/1.0")  |  self.tool_requires("cmake/3.27")
            for (m in Regex("(tool_requires|build_requires|test_requires|requires)\\s*(?:=|\\()\\s*([^\\n)]*)").findAll(text)) {
                val scope = when (m.groupValues[1]) { "requires" -> "main"; "test_requires" -> "test"; else -> "build" }
                quoted(m.groupValues[2]).forEach { ref(it, scope) }
            }
            return out
        }
        var scope: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                scope = when (line.trim('[', ']')) { "requires" -> "main"; "tool_requires", "build_requires" -> "build"; "test_requires" -> "test"; else -> null }
                continue
            }
            ref(line, scope ?: continue)
        }
        return out
    }

    fun meson(text: String, path: String): List<LibraryDto> =
        Regex("\\bdependency\\s*\\(\\s*'([^']+)'([^)]*)\\)").findAll(text).mapNotNull { m ->
            val name = m.groupValues[1]
            if (name == "threads") return@mapNotNull null
            val version = Regex("version\\s*:\\s*'([^']+)'").find(m.groupValues[2])?.groupValues?.get(1).orEmpty()
            LibraryDto(name, "", version, "Meson", "main", path)
        }.toList()

    // ---- Ruby / Dart / Swift / Elixir ------------------------------------------------------------

    private val GEM = Regex("^(?:gem|\\w+\\.add_(development_|runtime_)?dependency)\\s*\\(?\\s*[\"']([^\"']+)[\"'](.*)")

    fun ruby(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        val groups = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(" #").trim()
            if (line.startsWith("#")) continue
            Regex("^group\\s+(.+?)\\s+do\\b").find(line)?.let { groups += it.groupValues[1]; continue }
            if (line == "end" && groups.isNotEmpty()) { groups.removeAt(groups.size - 1); continue }
            val m = GEM.find(line) ?: continue
            val rest = m.groupValues[3]
            // Version constraints are the quoted args before any keyword option (group:, require:, git:...).
            val beforeOptions = Regex("\\b\\w+:\\s").find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
            val inGroups = (groups.joinToString(" ") + " " + (Regex("group[s]?:\\s*(.+)").find(rest)?.groupValues?.get(1) ?: "")).lowercase()
            val scope = when {
                m.groupValues[1] == "development_" -> "dev"
                ":test" in inGroups -> "test"
                ":development" in inGroups -> "dev"
                else -> "main"
            }
            out += LibraryDto(m.groupValues[2], "", quoted(beforeOptions).joinToString(", "), "RubyGems", scope, path)
        }
        return out
    }

    fun pubspec(text: String, path: String): List<LibraryDto> {
        val out = ArrayList<LibraryDto>()
        var scope: String? = null
        var current = -1      // index in out of the entry whose nested block we are in
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.trimStart().startsWith('#')) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.substringBefore(" #").trim()
            if (indent == 0) {
                scope = when (line) { "dependencies:" -> "main"; "dev_dependencies:" -> "dev"; else -> null }
                current = -1
                continue
            }
            val sc = scope ?: continue
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim().trim('"', '\'')
            if (indent <= 2) {
                out += LibraryDto(key, "", value, "pub", sc, path)
                current = out.size - 1
            } else if (current >= 0) {
                if (key == "sdk") { out.removeAt(current); current = -1 }       // flutter: sdk: flutter
                else if (key == "version") out[current] = out[current].copy(version = value)
            }
        }
        return out
    }

    fun swift(text: String, path: String): List<LibraryDto> =
        Regex("\\.package\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)").findAll(text).mapNotNull { m ->
            val body = m.groupValues[1]
            val url = Regex("url:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return@mapNotNull null   // path: packages are local
            val name = Regex("name:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: url.removeSuffix(".git").trimEnd('/').substringAfterLast('/')
            val version = Regex("(?:from|exact|branch|revision):\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: Regex("\"(\\d+(?:\\.\\d+)*)\"\\s*\\.\\.").find(body)?.groupValues?.get(1) ?: ""
            LibraryDto(name, repoOwner(url), version, "SwiftPM", "main", path)
        }.toList()

    fun mix(text: String, path: String): List<LibraryDto> =
        Regex("\\{\\s*:(\\w+)\\s*,\\s*(?:\"([^\"]+)\")?([^}]*)}").findAll(text).mapNotNull { m ->
            val rest = m.groupValues[3]
            if (m.groupValues[2].isEmpty() && "github:" !in rest && "git:" !in rest && "hex:" !in rest) return@mapNotNull null
            val only = Regex("only:\\s*(\\[[^]]*]|:\\w+)").find(rest)?.groupValues?.get(1).orEmpty()
            val scope = when {
                only.isEmpty() -> "main"
                ":dev" in only -> "dev"
                ":test" in only -> "test"
                else -> "main"
            }
            LibraryDto(m.groupValues[1], "", m.groupValues[2], "Hex", scope, path)
        }.toList()

    // ---- .NET ----------------------------------------------------------------------------------

    private val NUGET = Regex("<(PackageReference|PackageVersion|package)\\b([^>]*?)(?:/>|>(.*?)</\\1>)", RegexOption.DOT_MATCHES_ALL)

    fun nuget(text: String, path: String): List<LibraryDto> {
        val xml = XML_COMMENT.replace(text, "")
        fun attr(tag: String, a: String) = Regex("\\b$a\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
        fun child(body: String, t: String) = Regex("<$t>\\s*([^<]*?)\\s*</$t>").find(body)?.groupValues?.get(1)
        return NUGET.findAll(xml).mapNotNull { m ->
            val attrs = m.groupValues[2]
            val body = m.groupValues[3]
            val name = attr(attrs, "Include") ?: attr(attrs, "Update") ?: attr(attrs, "id") ?: return@mapNotNull null
            val version = attr(attrs, "Version") ?: attr(attrs, "version") ?: child(body, "Version") ?: ""
            val dev = (attr(attrs, "PrivateAssets") ?: child(body, "PrivateAssets")).equals("all", ignoreCase = true) ||
                attr(attrs, "developmentDependency") == "true"
            val n = name.lowercase()
            val scope = when {
                "test" in n || n.startsWith("xunit") || n.startsWith("nunit") || n.startsWith("moq") || n.startsWith("coverlet") -> "test"
                dev -> "dev"
                else -> "main"
            }
            LibraryDto(name, "", version, "NuGet", scope, path)
        }.toList()
    }

    // ---- git submodules and vendored copies --------------------------------------------------------

    /** (library, submodule dir) per `[submodule "..."]` in `.gitmodules`. */
    fun gitSubmodules(text: String, path: String): List<Pair<LibraryDto, String>> {
        val out = ArrayList<Pair<LibraryDto, String>>()
        val lines = text.lines()
        val header = Regex("^\\s*\\[submodule\\s+\"([^\"]+)\"]")
        var i = 0
        while (i < lines.size) {
            val head = header.find(lines[i])
            if (head == null) { i++; continue }
            val start = i++
            var dir = ""
            var url = ""
            while (i < lines.size && !lines[i].trimStart().startsWith("[")) {
                keyValue(lines[i].trim())?.let { (k, v) -> if (k == "path") dir = v.trim() else if (k == "url") url = v.trim() }
                i++
            }
            if (dir.isEmpty()) continue
            val httpUrl = url.replace(Regex("^git@([^:]+):"), "https://$1/")
            val name = httpUrl.removeSuffix(".git").trimEnd('/').substringAfterLast('/').ifEmpty { head.groupValues[1].substringAfterLast('/') }
            out += LibraryDto(name, repoOwner(httpUrl), "", "Git submodule", "main", path, start + 1) to PathNorm.of(dir).trimEnd('/')
        }
        return out
    }

    private val VENDOR_DIRS = setOf("third_party", "thirdparty", "third-party", "3rdparty", "3rd_party", "external", "extern", "vendor", "vendored")

    /**
     * (directory, library) for each child directory of a vendor-style directory (`third_party/zlib/...`): copies
     * of libraries checked into the repo. Skips git submodules (reported as such), Go's `vendor/` (its modules are
     * already in go.mod) and loose files directly in the vendor directory. The library points at its README,
     * else its CMakeLists.txt, else its shallowest file.
     */
    fun vendored(paths: List<String>, submoduleDirs: Set<String>, isGo: Boolean): List<Pair<String, LibraryDto>> {
        val byDir = LinkedHashMap<String, MutableList<String>>()
        for (p in paths) {
            val segs = p.split('/')
            val vi = segs.indexOfFirst { it.lowercase() in VENDOR_DIRS }
            if (vi < 0 || vi + 2 >= segs.size) continue               // need <vendor>/<lib>/<file>
            if (isGo && segs[vi] == "vendor") continue
            val dir = segs.take(vi + 2).joinToString("/")
            if (submoduleDirs.any { dir == it || dir.startsWith("$it/") || it.startsWith("$dir/") }) continue
            byDir.getOrPut(dir) { ArrayList() } += p
        }
        return byDir.map { (dir, files) ->
            val pick = files.firstOrNull { it.removePrefix("$dir/").lowercase().startsWith("readme") }
                ?: files.firstOrNull { it == "$dir/CMakeLists.txt" }
                ?: files.minWith(compareBy<String>({ it.count { c -> c == '/' } }, { it }))
            dir to LibraryDto(dir.substringAfterLast('/'), "", "", "Vendored", "main", pick)
        }
    }

    // ---- tiny TOML helpers (enough for manifests) --------------------------------------------

    /** Section name ("" before the first header) -> its lines; multi-line values are joined onto one line unless [keepRaw]. */
    private fun tomlSections(text: String, keepRaw: Boolean = false): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current = ""
        var pending: StringBuilder? = null
        var depth = 0
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore(" #").trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (pending == null && line.startsWith('[') && !line.startsWith("[[")) {
                current = line.trim('[', ']').trim()
                out.getOrPut(current) { ArrayList() }
                continue
            }
            if (keepRaw) { out.getOrPut(current) { ArrayList() } += line; continue }
            val sb = pending ?: StringBuilder()
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(line)
            depth += line.count { it == '[' || it == '{' } - line.count { it == ']' || it == '}' }
            if (depth > 0) { pending = sb; continue }
            depth = 0
            pending = null
            out.getOrPut(current) { ArrayList() } += sb.toString()
        }
        return out
    }

    private fun keyValue(line: String): Pair<String, String>? {
        val i = line.indexOf('=')
        if (i <= 0) return null
        val k = line.substring(0, i).trim().trim('"', '\'')
        return if (k.isEmpty()) null else k to line.substring(i + 1).trim()
    }

    private fun unquote(v: String): String? {
        val t = v.trim()
        return if (t.length >= 2 && (t[0] == '"' || t[0] == '\'') && t.last() == t[0]) t.substring(1, t.length - 1) else null
    }

    /** `{ module = "g:a", version.ref = "x" }` -> map. Nested tables are ignored. */
    private fun inlineTable(v: String): Map<String, String> {
        val t = v.trim()
        if (!t.startsWith('{')) return emptyMap()
        return Regex("([\\w.\\-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(t).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun quoted(s: String): List<String> = Regex("[\"']([^\"']+)[\"']").findAll(s).map { it.groupValues[1] }.toList()

    private fun stripComments(code: String): String =
        code.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            // Keep "//" inside strings such as URLs.
            if (i >= 0 && line.substring(0, i).count { it == '"' } % 2 == 0) line.substring(0, i) else line
        }
}
