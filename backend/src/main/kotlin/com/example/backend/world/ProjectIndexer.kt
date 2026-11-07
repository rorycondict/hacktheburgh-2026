package com.example.backend.world

import com.example.backend.session.PathUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** [deps]: file path -> paths it refers to (see [DependencyScanner]). */
data class IndexResult(val files: List<FileInfo>, val revision: String, val deps: Map<String, Set<String>> = emptyMap())

/** Walks project content: LOC, language and git churn per file. */
object ProjectIndexer {
    private val LOG = logger<ProjectIndexer>()

    val SKIP_DIRS = setOf(
        ".git", ".idea", ".gradle", ".intellijPlatform", ".kotlin", "build", "out", "node_modules", ".overworld",
        "target", "dist", ".venv", "venv", "__pycache__", ".next", ".cache",
    )
    private const val MAX_FILES = 20_000
    private const val MAX_LOC_BYTES = 1L shl 20

    suspend fun index(project: Project): IndexResult {
        val base = project.basePath ?: return IndexResult(emptyList(), "")
        val excluded = try {
            readAction {
                ModuleManager.getInstance(project).modules.flatMap { m ->
                    ModuleRootManager.getInstance(m).excludeRootUrls.map { PathUtil.normalise(VfsUtilCore.urlToPath(it)) }
                }
            }.toSet()
        } catch (e: Exception) {
            LOG.debug(e); emptySet()
        }
        return withContext(Dispatchers.IO) {
            val root = Path.of(base)
            val churn = GitInfo.churn(root)
            val revision = GitInfo.head(root)
            val files = walk(root, excluded, churn)
            val deps = try { DependencyScanner.scan(root, files) } catch (e: Exception) { LOG.debug(e); emptyMap() }
            IndexResult(files, revision, deps)
        }
    }

    suspend fun walk(root: Path, excluded: Set<String>, churn: Map<String, Int>): List<FileInfo> {
        val ctx = currentCoroutineContext()
        val out = ArrayList<FileInfo>()
        val ignoreCase = PathUtil.caseInsensitive
        val excludedNorm = excluded.map { if (ignoreCase) it.lowercase() else it }.toSet()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!ctx.isActive || out.size >= MAX_FILES) return FileVisitResult.TERMINATE
                if (dir == root) return FileVisitResult.CONTINUE
                val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                if (name in SKIP_DIRS || name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                val norm = PathUtil.normalise(dir.toString()).let { if (ignoreCase) it.lowercase() else it }
                if (norm in excludedNorm) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val rel = PathUtil.normalise(root.relativize(file).toString())
                val name = file.fileName.toString()
                if (name == ".DS_Store" || name.endsWith(".iml") || name == ".env") return FileVisitResult.CONTINUE
                val loc = if (attrs.size() < MAX_LOC_BYTES && FileKinds.isProbablyText(rel)) countLines(file) else 0
                out += FileInfo(rel, loc, FileKinds.language(rel), churn[rel] ?: 0, attrs.size())
                return if (out.size >= MAX_FILES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        ctx.ensureActive()
        return out
    }

    /** Newline count (plus a trailing partial line); 0 for binary content. */
    fun countLines(file: Path): Int = try {
        val bytes = Files.readAllBytes(file)
        if (bytes.take(8000).any { it == 0.toByte() }) 0
        else {
            var n = bytes.count { it == '\n'.code.toByte() }
            if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) n++
            n
        }
    } catch (_: Exception) {
        0
    }
}

/** Git CLI helpers; every failure (no git, not a repo, timeout) yields an empty result. */
object GitInfo {
    fun head(dir: Path): String = run(dir, listOf("git", "rev-parse", "HEAD"), 5)?.trim()?.takeIf { it.length >= 7 } ?: ""

    /** Commits touching each path (relative to [dir]) in the last 30 days. */
    fun churn(dir: Path): Map<String, Int> {
        val out = run(dir, listOf("git", "log", "--since=30.days", "--name-only", "--relative", "--pretty=format:"), 20) ?: return emptyMap()
        val counts = HashMap<String, Int>()
        out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { counts.merge(it.replace('\\', '/'), 1, Int::plus) }
        return counts
    }

    private fun run(dir: Path, cmd: List<String>, timeoutSec: Long): String? = try {
        val p = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start()
        p.outputStream.close()
        val result = StringBuilder()
        val reader = Thread {
            try { result.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        Thread { try { p.errorStream.readAllBytes() } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly(); null
        } else {
            reader.join(2000)
            if (p.exitValue() == 0) result.toString() else null
        }
    } catch (_: Exception) {
        null
    }
}
