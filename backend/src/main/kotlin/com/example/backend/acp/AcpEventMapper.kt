package com.example.backend.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

enum class Direction { TO_AGENT, FROM_AGENT }

/** One line of the tap wire protocol / raw log. */
sealed interface TapLine {
    data class Hello(val token: String, val agent: String, val tapId: String, val pid: Long?) : TapLine
    /** [seq] is the tap's frame number (null in old logs); servers use it to skip frames replayed on reconnect. */
    data class Frame(val dir: Direction, val ts: Long, val line: String, val seq: Long? = null) : TapLine

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses one envelope line; null if it is not one we understand. Never throws. */
        fun parse(text: String): TapLine? = try {
            val o = json.parseToJsonElement(text) as? JsonObject
            when (o?.str("type")) {
                "hello" -> Hello(o.str("token") ?: "", o.str("agent") ?: "agent", o.str("tapId") ?: "", o.long("pid"))
                "frame" -> {
                    val dir = when (o.str("dir")) {
                        "to_agent" -> Direction.TO_AGENT
                        "from_agent" -> Direction.FROM_AGENT
                        else -> null
                    }
                    val line = o.str("line")
                    if (dir == null || line == null) null else Frame(dir, o.long("ts") ?: System.currentTimeMillis(), line, o.long("seq"))
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Maps raw ACP JSON-RPC lines to [AgentEvent]s. Stateful per tap connection so that requests can be
 * correlated with their responses. Tolerant: unknown methods and fields are ignored, and bad input
 * yields no events rather than an exception.
 */
class AcpEventMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Pending(val method: String, val params: JsonObject?)

    /** Requests sent by the client (Air, or injected by us), keyed by id; answered from the agent. */
    private val clientRequests = HashMap<String, Pending>()
    /** Requests sent by the agent to the client, keyed by id; answered by the client. */
    private val agentRequests = HashMap<String, Pending>()

    /** sessionId -> cwd, as learned from session/new and session/load. */
    val sessionCwds: MutableMap<String, String?> = LinkedHashMap()

    /** Registers a request we injected through the tap so that its response is understood. */
    fun expectInjected(id: String, method: String, params: JsonObject) {
        clientRequests[id] = Pending(method, params)
    }

    fun map(frame: TapLine.Frame): List<AgentEvent> = map(frame.dir, frame.ts, frame.line)

    fun map(dir: Direction, ts: Long, line: String): List<AgentEvent> = try {
        val msg = json.parseToJsonElement(line) as? JsonObject
        if (msg == null) emptyList() else mapMessage(dir, ts, msg)
    } catch (_: Exception) {
        emptyList()
    }

    private fun mapMessage(dir: Direction, ts: Long, msg: JsonObject): List<AgentEvent> {
        val method = msg.str("method")
        val id = msg["id"]?.takeIf { it !is JsonNull }?.let(::idKey)
        val params = msg["params"] as? JsonObject
        if (method != null) {
            if (id != null) {
                val pending = Pending(method, params)
                if (dir == Direction.TO_AGENT) clientRequests[id] = pending else agentRequests[id] = pending
            }
            return if (dir == Direction.TO_AGENT) clientRequest(ts, method, params) else agentMessage(ts, method, params, id != null)
        }
        if (id == null) return emptyList()
        // A response: from the agent answers a client request, and vice versa.
        val pending = (if (dir == Direction.FROM_AGENT) clientRequests.remove(id) else agentRequests.remove(id)) ?: return emptyList()
        val result = msg["result"]
        val error = msg["error"] as? JsonObject
        return if (dir == Direction.FROM_AGENT) agentResponse(ts, pending, result, error) else clientResponse(ts, pending, result, error)
    }

    // ---- client -> agent -------------------------------------------------------------------

    private fun clientRequest(ts: Long, method: String, params: JsonObject?): List<AgentEvent> {
        val sessionId = params?.str("sessionId") ?: return emptyList()
        return when (method) {
            "session/prompt" -> {
                val text = contentText(params["prompt"])
                listOf(AgentEvent.TurnStarted(sessionId, ts, text.preview(120), text.length))
            }
            "session/load" -> {
                sessionCwds[sessionId] = params.str("cwd")
                emptyList()
            }
            "session/set_mode" -> params.str("modeId")?.let { listOf(AgentEvent.ConfigChanged(sessionId, ts, null, it)) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun agentResponse(ts: Long, req: Pending, result: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val r = result as? JsonObject
        val p = req.params
        return when (req.method) {
            "session/new", "session/load" -> {
                val sessionId = r?.str("sessionId") ?: p?.str("sessionId") ?: return emptyList()
                val cwd = p?.str("cwd")
                sessionCwds[sessionId] = cwd
                listOf(
                    AgentEvent.SessionStarted(
                        sessionId, ts, cwd,
                        configOptions = sessionOptions(r),
                        mode = (r?.get("modes") as? JsonObject)?.str("currentModeId"),
                        loaded = req.method == "session/load",
                    )
                )
            }
            "session/prompt" -> {
                val sessionId = p?.str("sessionId") ?: return emptyList()
                val stop = r?.str("stopReason") ?: if (error != null) "error" else null
                val usage = (r?.get("usage") as? JsonObject) ?: ((r?.get("_meta") as? JsonObject)?.get("usage") as? JsonObject)
                listOf(AgentEvent.TurnEnded(sessionId, ts, stop, usage?.let(::tokens)))
            }
            "session/set_config_option" -> {
                val sessionId = p?.str("sessionId") ?: return emptyList()
                if (error != null) return emptyList()
                val opts = r?.get("configOptions")?.let(::configOptions)
                if (opts != null) listOf(AgentEvent.ConfigChanged(sessionId, ts, opts, null))
                else listOf(AgentEvent.ConfigChanged(sessionId, ts, null, null, p.str("configId"), p.str("value")))
            }
            "session/set_model" -> {
                val sessionId = p?.str("sessionId") ?: return emptyList()
                if (error != null) emptyList()
                else listOf(AgentEvent.ConfigChanged(sessionId, ts, null, null, MODEL_OPTION_ID, p.str("modelId")))
            }
            "session/set_mode" -> {
                val sessionId = p?.str("sessionId") ?: return emptyList()
                if (error != null) emptyList()
                else listOf(AgentEvent.ConfigChanged(sessionId, ts, null, p.str("modeId"), MODE_OPTION_ID, p.str("modeId")))
            }
            else -> emptyList()
        }
    }

    // ---- agent -> client -------------------------------------------------------------------

    private fun agentMessage(ts: Long, method: String, params: JsonObject?, isRequest: Boolean): List<AgentEvent> {
        val sessionId = params?.str("sessionId") ?: return emptyList()
        return when (method) {
            "session/update" -> sessionUpdate(ts, sessionId, params["update"] as? JsonObject ?: return emptyList())
            "session/request_permission" -> {
                val tc = params["toolCall"] as? JsonObject
                val options = (params["options"] as? JsonArray).objects().mapNotNull { it.str("name") ?: it.str("optionId") }
                listOf(
                    AgentEvent.PermissionRequested(
                        sessionId, ts,
                        toolCallId = tc?.str("toolCallId") ?: "",
                        title = tc?.str("title") ?: "Permission requested",
                        kind = tc?.str("kind"),
                        options = options,
                        locations = locations(tc?.get("locations")),
                    )
                )
            }
            "fs/read_text_file" -> listOf(
                AgentEvent.FileHint(sessionId, ts, HintKind.READ, params.str("path"), params.int("line"), "Read ${fileName(params.str("path"))}", 0)
            )
            "fs/write_text_file" -> listOf(
                AgentEvent.FileHint(sessionId, ts, HintKind.EDIT, params.str("path"), null, "Write ${fileName(params.str("path"))}", params.str("content")?.length ?: 0)
            )
            "terminal/create" -> {
                val cmd = listOfNotNull(params.str("command")) + (params["args"] as? JsonArray).strings()
                listOf(AgentEvent.FileHint(sessionId, ts, HintKind.EXECUTE, params.str("cwd"), null, cmd.joinToString(" ").preview(80), 0))
            }
            else -> emptyList()
        }
    }

    private fun clientResponse(ts: Long, req: Pending, result: JsonElement?, error: JsonObject?): List<AgentEvent> {
        val p = req.params ?: return emptyList()
        val sessionId = p.str("sessionId") ?: return emptyList()
        val r = result as? JsonObject
        return when (req.method) {
            "session/request_permission" -> {
                val outcome = r?.get("outcome") as? JsonObject
                val text = when {
                    error != null -> "error"
                    outcome == null -> "unknown"
                    outcome.str("outcome") == "selected" -> {
                        val optionId = outcome.str("optionId")
                        val option = (p["options"] as? JsonArray).objects().firstOrNull { it.str("optionId") == optionId }
                        option?.str("kind") ?: optionId ?: "selected"
                    }
                    else -> outcome.str("outcome") ?: "unknown"
                }
                val tcId = (p["toolCall"] as? JsonObject)?.str("toolCallId") ?: ""
                listOf(AgentEvent.PermissionResolved(sessionId, ts, tcId, text))
            }
            "fs/read_text_file" -> {
                val n = r?.str("content")?.length ?: 0
                if (n > 0) listOf(AgentEvent.FileHint(sessionId, ts, HintKind.READ, p.str("path"), p.int("line"), "Read ${fileName(p.str("path"))}", n, announce = false))
                else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun sessionUpdate(ts: Long, sessionId: String, u: JsonObject): List<AgentEvent> {
        val kind = u.str("sessionUpdate") ?: return emptyList()
        return when (kind) {
            "agent_message_chunk" -> {
                val t = contentText(u["content"])
                listOf(AgentEvent.Message(sessionId, ts, t.preview(200), t.length))
            }
            "agent_thought_chunk" -> {
                val t = contentText(u["content"])
                listOf(AgentEvent.Thought(sessionId, ts, t.preview(200), t.length))
            }
            "tool_call" -> listOf(
                AgentEvent.ToolStarted(
                    sessionId, ts,
                    toolCallId = u.str("toolCallId") ?: "",
                    kind = u.str("kind") ?: "other",
                    title = u.str("title") ?: "",
                    status = u.str("status") ?: "pending",
                    locations = locations(u["locations"]),
                    chars = size(u["rawInput"]) + size(u["content"]),
                )
            )
            "tool_call_update" -> listOf(
                AgentEvent.ToolUpdated(
                    sessionId, ts,
                    toolCallId = u.str("toolCallId") ?: "",
                    kind = u.str("kind"),
                    title = u.str("title"),
                    status = u.str("status"),
                    locations = u["locations"]?.let(::locations),
                    // Updates usually repeat or replace earlier fields; the reducer keeps a per-call high-water mark.
                    chars = size(u["content"]) + size(u["rawOutput"]),
                )
            )
            "plan", "plan_update" -> {
                val entries = (u["entries"] ?: (u["plan"] as? JsonObject)?.get("entries")) as? JsonArray
                listOf(AgentEvent.PlanUpdated(sessionId, ts, entries.objects().map {
                    PlanEntry(it.str("content") ?: "", it.str("priority") ?: "medium", it.str("status") ?: "pending")
                }))
            }
            "usage_update" -> {
                val cost = u["cost"] as? JsonObject
                listOf(
                    AgentEvent.UsageUpdated(
                        sessionId, ts,
                        used = u.long("used"),
                        size = u.long("size"),
                        costAmount = cost?.double("amount"),
                        currency = cost?.str("currency"),
                    )
                )
            }
            "config_option_update", "config_options_update" ->
                listOf(AgentEvent.ConfigChanged(sessionId, ts, u["configOptions"]?.let(::configOptions) ?: emptyList(), null))
            "current_mode_update" -> {
                val m = u.str("currentModeId") ?: u.str("modeId")
                listOf(AgentEvent.ConfigChanged(sessionId, ts, null, m, MODE_OPTION_ID, m))
            }
            else -> if (kind.startsWith("_")) listOf(AgentEvent.Custom(sessionId, ts, kind, u)) else emptyList()
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun sessionOptions(r: JsonObject?): List<ConfigOption> {
        if (r == null) return emptyList()
        val opts = r["configOptions"]?.let(::configOptions).orEmpty().toMutableList()
        val models = r["models"] as? JsonObject
        if (models != null && opts.none { isModelOption(it) }) {
            val values = (models["availableModels"] as? JsonArray).objects().mapNotNull {
                val v = it.str("modelId") ?: it.str("id") ?: return@mapNotNull null
                ConfigValue(v, it.str("name") ?: v)
            }
            val current = models.str("currentModelId") ?: values.firstOrNull()?.value
            if (current != null) opts += ConfigOption(MODEL_OPTION_ID, "Model", "model", current, values)
        }
        val modes = r["modes"] as? JsonObject
        if (modes != null && opts.none { it.category == "mode" || it.id == "mode" }) {
            val values = (modes["availableModes"] as? JsonArray).objects().mapNotNull {
                val v = it.str("id") ?: it.str("modeId") ?: return@mapNotNull null
                ConfigValue(v, it.str("name") ?: v)
            }
            val current = modes.str("currentModeId") ?: values.firstOrNull()?.value
            if (current != null) opts += ConfigOption(MODE_OPTION_ID, "Mode", "mode", current, values)
        }
        return opts
    }

    private fun configOptions(e: JsonElement): List<ConfigOption> = (e as? JsonArray).objects().mapNotNull { o ->
        val id = o.str("id") ?: return@mapNotNull null
        val values = ArrayList<ConfigValue>()
        fun collect(arr: JsonArray?) {
            for (v in arr.objects()) {
                val nested = v["options"] as? JsonArray
                if (nested != null) collect(nested) // grouped options
                else {
                    val value = v.str("value") ?: v.str("id") ?: continue
                    values += ConfigValue(value, v.str("name") ?: value)
                }
            }
        }
        collect(o["options"] as? JsonArray ?: o["values"] as? JsonArray)
        val current = o.str("currentValue") ?: o["currentValue"]?.let { (it as? JsonPrimitive)?.booleanOrNull?.toString() } ?: ""
        ConfigOption(id, o.str("name") ?: id, o.str("category"), current, values)
    }

    private fun tokens(u: JsonObject): ReportedTokens? {
        val input = u.long("inputTokens") ?: u.long("input_tokens") ?: u.long("promptTokens") ?: u.long("prompt_tokens")
        val output = u.long("outputTokens") ?: u.long("output_tokens") ?: u.long("completionTokens") ?: u.long("completion_tokens")
        if (input == null && output == null) return null
        return ReportedTokens(
            inputTokens = input ?: 0,
            outputTokens = output ?: 0,
            cachedReadTokens = u.long("cachedReadTokens") ?: u.long("cached_read_tokens") ?: u.long("cachedInputTokens") ?: 0,
            thoughtTokens = u.long("thoughtTokens") ?: u.long("reasoningTokens") ?: u.long("thought_tokens") ?: 0,
        )
    }

    private fun locations(e: JsonElement?): List<Location> = (e as? JsonArray).objects().mapNotNull { o ->
        val path = o.str("path") ?: return@mapNotNull null
        Location(path, o.int("line"))
    }

    /** Concatenated text of a ContentBlock or a list of them. */
    private fun contentText(e: JsonElement?): String = when (e) {
        is JsonObject -> when {
            e.str("text") != null -> e.str("text")!!
            e["content"] != null -> contentText(e["content"])
            e.str("type") == "resource_link" -> e.str("uri") ?: ""
            e["resource"] is JsonObject -> (e["resource"] as JsonObject).str("text") ?: ""
            else -> ""
        }
        is JsonArray -> e.joinToString("") { contentText(it) }
        is JsonPrimitive -> e.contentOrNull ?: ""
        else -> ""
    }

    private fun size(e: JsonElement?): Int = when (e) {
        null, JsonNull -> 0
        is JsonPrimitive -> e.contentOrNull?.length ?: 0
        else -> e.toString().length
    }

    private fun idKey(e: JsonElement): String = (e as? JsonPrimitive)?.contentOrNull ?: e.toString()

    private fun fileName(path: String?): String = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: "file"

    companion object {
        /** Synthetic option ids for agents that expose `models` / `modes` instead of `configOptions`. */
        const val MODEL_OPTION_ID = "_model"
        const val MODE_OPTION_ID = "_mode"

        fun isModelOption(o: ConfigOption): Boolean =
            o.category.equals("model", true) || o.id.contains("model", true) || o.category?.contains("model", true) == true

        fun isEffortOption(o: ConfigOption): Boolean {
            val keys = listOf("effort", "thought", "reasoning", "thinking")
            return keys.any { k -> o.id.contains(k, true) || o.category?.contains(k, true) == true }
        }

        fun isModeOption(o: ConfigOption): Boolean = o.category.equals("mode", true) || o.id.equals("mode", true) || o.id == MODE_OPTION_ID
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>() ?: emptyList()
internal fun JsonArray?.strings(): List<String> = this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
internal fun String.preview(max: Int): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= max) oneLine else oneLine.take(max - 1) + "…"
}
