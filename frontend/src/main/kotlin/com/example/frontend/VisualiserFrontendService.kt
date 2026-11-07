package com.example.frontend

import com.example.model.SessionDto
import com.example.model.TapStatusDto
import com.example.model.WorldDto
import com.example.model.WrapResultDto
import com.example.rpc.VisualiserRpcApi
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Frontend view of the backend's world and sessions. Everything crosses the process
 * boundary through [VisualiserRpcApi]; calls are wrapped in `durable {}` so they survive reconnects.
 */
@Service(Service.Level.PROJECT)
class VisualiserFrontendService(private val project: Project, scope: CoroutineScope) {
    companion object {
        fun getInstance(project: Project): VisualiserFrontendService = project.getService(VisualiserFrontendService::class.java)
    }

    val world: StateFlow<WorldDto?> = flow {
        durable {
            VisualiserRpcApi.getInstance().world(project.projectId()).collect { emit(it) }
        }
    }.stateIn(scope, SharingStarted.Lazily, null)

    val sessions: StateFlow<List<SessionDto>> = flow {
        durable {
            VisualiserRpcApi.getInstance().sessions(project.projectId()).collect { emit(it) }
        }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    /** The file selected in the user's editor (project-relative), shown on the map as the user's character. */
    val userPosition: StateFlow<String?> = flow {
        durable {
            VisualiserRpcApi.getInstance().userPosition(project.projectId()).collect { emit(it) }
        }
    }.stateIn(scope, SharingStarted.Lazily, null)

    /** Stable colour slot per session id, shared by the map and the HUD. */
    private val colorSlots = ConcurrentHashMap<String, Int>()
    private val nextSlot = AtomicInteger()
    fun colorSlot(sessionId: String): Int = colorSlots.computeIfAbsent(sessionId) { nextSlot.getAndIncrement() }

    suspend fun rebuildWorld(): Unit = call { rebuildWorld(project.projectId()) }
    suspend fun openFile(path: String, line: Int? = null): Unit = call { openFile(project.projectId(), path, line) }
    suspend fun wrapAgents(): WrapResultDto = call { wrapAgents() }
    suspend fun startReplay(logFile: String? = null): String = call { startReplay(project.projectId(), logFile) }
    suspend fun setConfigOption(sessionId: String, configId: String, value: String): Boolean =
        call { setConfigOption(project.projectId(), sessionId, configId, value) }
    suspend fun ask(path: String, question: String): String = call(timeoutMs = 180_000) { ask(project.projectId(), path, question) }
    suspend fun status(): TapStatusDto = call { status() }

    private suspend fun <T> call(timeoutMs: Long = 30_000, body: suspend VisualiserRpcApi.() -> T): T =
        withTimeout(timeoutMs) { durable { VisualiserRpcApi.getInstance().body() } }
}
