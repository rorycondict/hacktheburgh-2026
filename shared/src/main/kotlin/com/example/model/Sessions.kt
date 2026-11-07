package com.example.model

import kotlinx.serialization.Serializable

/** What an agent is currently doing; drives the character animation. */
enum class ActivityKind { IDLE, THINKING, MESSAGE, READ, EDIT, DELETE, MOVE, SEARCH, EXECUTE, FETCH, OTHER, WAITING_PERMISSION }

/** The current activity of a session. */
@Serializable
data class ActivityDto(
    val kind: ActivityKind,
    /** Tool-call title or short text; "" if none. */
    val title: String,
    /** Project-relative if inside the project, else absolute; null if none. */
    val path: String?,
    val line: Int?,
    val failed: Boolean,
    val toolCallId: String?,
    val sinceMillis: Long,
)

/** Token usage, context and cost for a session. */
@Serializable
data class UsageDto(
    val inputTokens: Long,
    val outputTokens: Long,
    val tokensEstimated: Boolean,
    /** From usage_update, if the agent sends it. */
    val contextUsed: Long?,
    val contextSize: Long?,
    val costSoFar: Double,
    /** "USD". */
    val currency: String,
    /** False only when the agent reported cost. */
    val costEstimated: Boolean,
    /** Null when there is not enough data. */
    val projectedCostLow: Double?,
    val projectedCostHigh: Double?,
    /** Model id -> cost of the same tokens on that model. */
    val alternativeCosts: Map<String, Double>,
)

/** One entry of the agent's plan (quest log). [status]: pending | in_progress | completed. */
@Serializable
data class PlanEntryDto(val content: String, val priority: String, val status: String)

/** A pending permission request from the agent. */
@Serializable
data class PermissionDto(val toolCallId: String, val title: String, val options: List<String>)

/** A recent tool call made by the agent. */
@Serializable
data class ToolCallDto(val toolCallId: String, val kind: String, val title: String, val status: String, val path: String?)

/** One selectable value of a config option. */
@Serializable
data class ConfigValueDto(val value: String, val name: String)

/** A session config option exposed by the agent (model, effort, mode...). */
@Serializable
data class ConfigOptionDto(
    val id: String,
    val name: String,
    /** E.g. "model", "thought_level"/"effort", "mode". */
    val category: String?,
    val currentValue: String,
    val values: List<ConfigValueDto>,
)

/** Full state of one agent session, as reduced by the backend. */
@Serializable
data class SessionDto(
    val sessionId: String,
    /** From the tap's --name (the acp.json key). */
    val agentName: String,
    val cwd: String,
    val model: String?,
    val effort: String?,
    val mode: String?,
    val turnActive: Boolean,
    val activity: ActivityDto,
    val usage: UsageDto,
    val plan: List<PlanEntryDto>,
    val pendingPermission: PermissionDto?,
    /** Most recent last, max 50. */
    val touchedFiles: List<String>,
    /** Most recent last, max 20. */
    val recentToolCalls: List<ToolCallDto>,
    val configOptions: List<ConfigOptionDto>,
    val lastEventMillis: Long,
    /** True if driven by a replayed log. */
    val replay: Boolean,
)

/** Result of "Wrap my agents" on ~/.jetbrains/acp.json. */
@Serializable
data class WrapResultDto(val acpJsonPath: String, val wrapped: List<String>, val alreadyWrapped: List<String>, val message: String)

/** Status of the backend tap server. */
@Serializable
data class TapStatusDto(val port: Int, val registryFile: String, val logsDir: String, val connectedTaps: Int)
