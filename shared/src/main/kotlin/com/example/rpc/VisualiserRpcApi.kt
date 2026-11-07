package com.example.rpc

import com.example.model.SessionDto
import com.example.model.TapStatusDto
import com.example.model.WorldDto
import com.example.model.WrapResultDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/** Frontend -> backend API for the map, agent sessions and tap control. */
@Rpc
interface VisualiserRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): VisualiserRpcApi = RemoteApiProviderService.resolve(remoteApiDescriptor<VisualiserRpcApi>())
    }

    /** Emits null while the first index is running, then each new world. */
    suspend fun world(projectId: ProjectId): Flow<WorldDto?>

    /** Emits the current list of agent sessions for this project on every change. */
    suspend fun sessions(projectId: ProjectId): Flow<List<SessionDto>>

    /** Re-indexes the project and emits a new world. */
    suspend fun rebuildWorld(projectId: ProjectId)

    /** path: project-relative or absolute. line: 1-based, optional. */
    suspend fun openFile(projectId: ProjectId, path: String, line: Int?)

    /** Adds tap-wrapped copies of each ~/.jetbrains/acp.json entry. */
    suspend fun wrapAgents(): WrapResultDto

    /** Replays a raw tap log into this project. logFile null = newest log in ~/.overworld/logs. Returns a status message. */
    suspend fun startReplay(projectId: ProjectId, logFile: String?): String

    /** Returns false if the session or option is unknown, or the agent is not behind a tap. */
    suspend fun setConfigOption(projectId: ProjectId, sessionId: String, configId: String, value: String): Boolean

    /** path: project-relative file or room dir. Returns the answer, or a message explaining why there isn't one. */
    suspend fun ask(projectId: ProjectId, path: String, question: String): String

    /** Current tap server status. */
    suspend fun status(): TapStatusDto
}
