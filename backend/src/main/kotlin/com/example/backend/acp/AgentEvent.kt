package com.example.backend.acp

import kotlinx.serialization.json.JsonElement

/** Version of the [AgentEvent] model; bump the major on breaking changes. */
const val AGENT_EVENT_SCHEMA_VERSION = 1

/** A file location attached to a tool call. [path] is as sent by the agent (usually absolute). */
data class Location(val path: String, val line: Int?)

data class ConfigValue(val value: String, val name: String)

/**
 * A session config option. Ids starting with `_` are synthesised by the mapper from the older
 * `models` / `modes` session fields and are set with `session/set_model` / `session/set_mode`.
 */
data class ConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val currentValue: String,
    val values: List<ConfigValue>,
)

data class PlanEntry(val content: String, val priority: String, val status: String)

/** Token counts reported by the agent (e.g. `usage` in a `session/prompt` response). */
data class ReportedTokens(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedReadTokens: Long,
    val thoughtTokens: Long,
)

enum class HintKind { READ, EDIT, EXECUTE }

/** Normalised event stream built from raw ACP frames. Renderers never see raw ACP. */
sealed interface AgentEvent {
    val sessionId: String
    val ts: Long
    val schemaVersion: Int get() = AGENT_EVENT_SCHEMA_VERSION

    data class SessionStarted(
        override val sessionId: String,
        override val ts: Long,
        val cwd: String?,
        val configOptions: List<ConfigOption>,
        val mode: String?,
        val loaded: Boolean,
    ) : AgentEvent

    data class TurnStarted(override val sessionId: String, override val ts: Long, val promptPreview: String, val promptChars: Int) : AgentEvent

    data class TurnEnded(override val sessionId: String, override val ts: Long, val stopReason: String?, val tokens: ReportedTokens?) : AgentEvent

    data class Thought(override val sessionId: String, override val ts: Long, val text: String, val chars: Int) : AgentEvent

    data class Message(override val sessionId: String, override val ts: Long, val text: String, val chars: Int) : AgentEvent

    data class ToolStarted(
        override val sessionId: String,
        override val ts: Long,
        val toolCallId: String,
        val kind: String,
        val title: String,
        val status: String,
        val locations: List<Location>,
        /** Size of rawInput + content, for token estimates. */
        val chars: Int,
    ) : AgentEvent

    data class ToolUpdated(
        override val sessionId: String,
        override val ts: Long,
        val toolCallId: String,
        val kind: String?,
        val title: String?,
        val status: String?,
        val locations: List<Location>?,
        val chars: Int,
    ) : AgentEvent

    data class PlanUpdated(override val sessionId: String, override val ts: Long, val entries: List<PlanEntry>) : AgentEvent

    data class PermissionRequested(
        override val sessionId: String,
        override val ts: Long,
        val toolCallId: String,
        val title: String,
        val kind: String?,
        val options: List<String>,
        val locations: List<Location>,
    ) : AgentEvent

    data class PermissionResolved(override val sessionId: String, override val ts: Long, val toolCallId: String, val outcome: String) : AgentEvent

    /** From `usage_update`. [costAmount] is the cumulative session cost when the agent sends it. */
    data class UsageUpdated(
        override val sessionId: String,
        override val ts: Long,
        val used: Long?,
        val size: Long?,
        val costAmount: Double?,
        val currency: String?,
    ) : AgentEvent

    /** Full replacement of the option list ([configOptions] non-null) and/or a mode change. */
    data class ConfigChanged(
        override val sessionId: String,
        override val ts: Long,
        val configOptions: List<ConfigOption>?,
        val mode: String?,
        /** Partial update of one option, e.g. after `session/set_model`. */
        val optionId: String? = null,
        val optionValue: String? = null,
    ) : AgentEvent

    /** Client-side file / terminal requests from the agent (`fs/read_text_file`, ...). */
    data class FileHint(
        override val sessionId: String,
        override val ts: Long,
        val kind: HintKind,
        val path: String?,
        val line: Int?,
        val title: String,
        val chars: Int,
        /** False for hints that only carry a token count (e.g. a read's response), so they don't change the activity. */
        val announce: Boolean = true,
    ) : AgentEvent

    /** Any `sessionUpdate` starting with `_`. */
    data class Custom(override val sessionId: String, override val ts: Long, val name: String, val raw: JsonElement) : AgentEvent
}
