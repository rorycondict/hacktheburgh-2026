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
import java.io.File

/** Plays a raw tap log (`~/.overworld/logs/<stamp>-<agent>.jsonl`) into this project through the normal mapper. */
@Service(Service.Level.PROJECT)
class ReplayService(private val project: Project, private val cs: CoroutineScope) {
    private var job: Job? = null

    @Synchronized
    fun start(logFile: String?): String {
        val file = logFile?.let(::File) ?: newestLog() ?: return "No tap logs in ${OverworldDirs.logs.absolutePath} yet."
        if (!file.isFile) return "Log not found: ${file.absolutePath}"
        val agent = agentName(file)
        val base = project.basePath ?: return "Project has no base path."
        job?.cancel()
        project.service<SessionService>().removeWhere { it.startsWith(PREFIX) }
        job = cs.launch(Dispatchers.IO) {
            val mapper = AcpEventMapper()
            val sessions = project.service<SessionService>()
            var prevTs: Long? = null
            try {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val frame = TapLine.parse(raw) as? TapLine.Frame ?: continue
                        prevTs?.let { prev -> delay((frame.ts - prev).coerceIn(0, MAX_GAP_MS)) }
                        prevTs = frame.ts
                        // Original gaps (capped), but events are stamped with the replay clock.
                        for (ev in mapper.map(frame.dir, System.currentTimeMillis(), frame.line)) {
                            sessions.apply(agent, ev, replay = true, cwdOverride = base, sessionId = PREFIX + ev.sessionId)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LOG.warn("Replay failed", e)
            }
        }
        return "Replaying ${file.name} as \"$agent\"."
    }

    private fun newestLog(): File? =
        OverworldDirs.logs.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.maxByOrNull { it.lastModified() }

    private fun agentName(file: File): String {
        // <yyyyMMdd-HHmmss>-<agent>.jsonl
        val stem = file.name.removeSuffix(".jsonl")
        val m = Regex("^\\d{8}-\\d{6}-(.+)$").find(stem)
        return m?.groupValues?.get(1) ?: stem
    }

    companion object {
        const val PREFIX = "replay:"
        private const val MAX_GAP_MS = 2_000L
        private val LOG = logger<ReplayService>()
    }
}
