package com.example.backend.replay

import com.example.backend.acp.AcpEventMapper
import com.example.backend.acp.TapLine
import com.example.backend.session.SessionService
import com.example.backend.tap.OverworldDirs
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File

/**
 * Plays a raw tap log (`~/.overworld/logs/<stamp>-<agent>.jsonl`) into this project through the normal mapper.
 * With no logs yet, it plays the bundled sample session, so the demo fallback works on a fresh machine.
 */
@Service(Service.Level.PROJECT)
class ReplayService(private val project: Project, private val cs: CoroutineScope) {
    private var job: Job? = null
    /** Bumped by every [start]; a replay only applies events while it is still the current generation. */
    private var generation = 0L

    private class Source(val name: String, val agent: String, val open: () -> BufferedReader)

    @Synchronized
    fun start(logFile: String?): String {
        val source = when {
            logFile != null -> File(logFile).takeIf { it.isFile }?.let(::fileSource) ?: return "Log not found: $logFile"
            else -> newestLog()?.let(::fileSource) ?: bundledSample() ?: return "No tap logs in ${OverworldDirs.logs.absolutePath} yet."
        }
        val base = project.basePath ?: return "Project has no base path."
        job?.cancel()
        val gen = ++generation
        project.service<SessionService>().removeWhere { it.startsWith(PREFIX) }
        job = cs.launch(Dispatchers.IO) {
            val mapper = AcpEventMapper()
            val sessions = project.service<SessionService>()
            var prevTs: Long? = null
            try {
                source.open().useLines { lines ->
                    for (raw in lines) {
                        val frame = TapLine.parse(raw) as? TapLine.Frame ?: continue
                        prevTs?.let { prev -> delay((frame.ts - prev).coerceIn(0, MAX_GAP_MS)) }
                        prevTs = frame.ts
                        // Original gaps (capped), but events are stamped with the replay clock.
                        val events = mapper.map(frame.dir, System.currentTimeMillis(), frame.line)
                        // Same monitor as start(): a newer replay can't clear sessions between our check and apply.
                        val current = synchronized(this@ReplayService) {
                            if (gen != generation) return@synchronized false
                            for (ev in events) sessions.apply(source.agent, ev, replay = true, cwdOverride = base, sessionId = PREFIX + ev.sessionId)
                            true
                        }
                        if (!current) return@useLines
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LOG.warn("Replay failed", e)
            }
        }
        return "Replaying ${source.name} as \"${source.agent}\"."
    }

    private fun fileSource(file: File) = Source(file.name, agentName(file.name)) { file.bufferedReader(Charsets.UTF_8) }

    private fun bundledSample(): Source? {
        if (ReplayService::class.java.getResource(SAMPLE) == null) return null
        return Source("the bundled sample session", "Sample agent") {
            ReplayService::class.java.getResourceAsStream(SAMPLE)!!.bufferedReader(Charsets.UTF_8)
        }
    }

    private fun newestLog(): File? =
        OverworldDirs.logs.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.maxByOrNull { it.lastModified() }

    private fun agentName(fileName: String): String {
        // <yyyyMMdd-HHmmss>-<agent>.jsonl
        val stem = fileName.removeSuffix(".jsonl")
        val m = Regex("^\\d{8}-\\d{6}-(.+)$").find(stem)
        return m?.groupValues?.get(1) ?: stem
    }

    companion object {
        const val PREFIX = "replay:"
        private const val MAX_GAP_MS = 2_000L
        private const val SAMPLE = "/floorplan/sample-session.jsonl"
        private val LOG = logger<ReplayService>()
    }
}
