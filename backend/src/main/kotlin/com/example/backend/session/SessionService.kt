package com.example.backend.session

import com.example.backend.acp.AgentEvent
import com.example.backend.acp.ConfigOption
import com.example.model.SessionDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Per-project session state, fed by the tap server and by replays. */
@Service(Service.Level.PROJECT)
class SessionService(project: Project, private val cs: CoroutineScope) {
    private val reducer = SessionReducer(project.basePath ?: "", Pricing.loadDefault())
    private val lock = Any()
    private val _sessions = MutableStateFlow<List<SessionDto>>(emptyList())
    val sessions: StateFlow<List<SessionDto>> = _sessions.asStateFlow()
    private var emitJob: Job? = null
    private val removals = HashMap<String, Job>()

    fun apply(agentName: String, event: AgentEvent, replay: Boolean = false, cwdOverride: String? = null, sessionId: String = event.sessionId) {
        synchronized(lock) {
            removals.remove(sessionId)?.cancel()
            reducer.apply(agentName, event, replay, cwdOverride, sessionId)
            scheduleEmit()
        }
    }

    /** The agent's tap went away: idle the character now, drop it after a while. */
    fun disconnected(sessionId: String) {
        synchronized(lock) {
            reducer.disconnect(sessionId, System.currentTimeMillis())
            scheduleEmit()
            removals[sessionId]?.cancel()
            removals[sessionId] = cs.launch {
                delay(120_000)
                synchronized(lock) {
                    reducer.remove(sessionId)
                    removals.remove(sessionId)
                    scheduleEmit()
                }
            }
        }
    }

    fun configOption(sessionId: String, configId: String): ConfigOption? = synchronized(lock) { reducer.configOption(sessionId, configId) }

    fun removeWhere(predicate: (String) -> Boolean) {
        synchronized(lock) {
            reducer.sessionIds().filter(predicate).forEach(reducer::remove)
            scheduleEmit()
        }
    }

    /** Coalesces bursts (text chunks) into at most one emission per ~40 ms. */
    private fun scheduleEmit() {
        if (emitJob?.isActive == true) return
        emitJob = cs.launch {
            delay(40)
            synchronized(lock) {
                _sessions.value = reducer.snapshot()
                emitJob = null
            }
        }
    }

    companion object {
        fun getInstance(project: Project): SessionService = project.service()
    }
}
