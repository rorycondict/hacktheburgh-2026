package com.example.backend.session

import com.example.backend.acp.AcpEventMapper
import com.example.backend.acp.AgentEvent
import com.example.backend.acp.ConfigOption
import com.example.backend.acp.HintKind
import com.example.backend.acp.PlanEntry
import com.example.backend.acp.preview
import com.example.model.ActivityDto
import com.example.model.ActivityKind
import com.example.model.ConfigOptionDto
import com.example.model.ConfigValueDto
import com.example.model.PermissionDto
import com.example.model.PlanEntryDto
import com.example.model.SessionDto
import com.example.model.ToolCallDto
import com.example.model.UsageDto
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure state machine: folds [AgentEvent]s into [SessionDto]s. Not thread-safe; the owning service
 * serialises access.
 */
class SessionReducer(private val projectRoot: String, private val pricing: Pricing) {

    private class State(val sessionId: String, var agentName: String) {
        var cwd: String = ""
        var roots: List<String> = emptyList()
        var replay = false
        var configOptions: List<ConfigOption> = emptyList()
        var mode: String? = null
        var turnActive = false
        var activity = ActivityDto(ActivityKind.IDLE, "", null, null, false, null, 0)
        var activityBeforePermission: ActivityDto? = null
        var plan: List<PlanEntry> = emptyList()
        var pendingPermission: PermissionDto? = null
        val touched = LinkedHashSet<String>()
        val toolCalls = LinkedHashMap<String, ToolCallDto>()
        val toolPaths = HashMap<String, Pair<String?, Int?>>()
        var lastEventMillis = 0L
        var textBuffer = StringBuilder()

        // tokens
        var reportedIn = 0L
        var reportedOut = 0L
        var reportedCached = 0L
        var anyReported = false
        var estIn = 0L      // chars, finished turns without reported usage
        var estOut = 0L
        var turnIn = 0L     // chars, current turn
        var turnOut = 0L
        var contextUsed: Long? = null
        var contextSize: Long? = null
        var reportedCost: Double? = null
        var reportedCurrency: String? = null

        // projection
        var costAtTurnStart = 0.0
        val turnCosts = ArrayList<Double>()
        var planStartCost: Double? = null
        val completionMarks = ArrayList<Double>()
    }

    private val sessions = LinkedHashMap<String, State>()

    fun apply(
        agentName: String,
        event: AgentEvent,
        replay: Boolean = false,
        cwdOverride: String? = null,
        sessionId: String = event.sessionId,
    ) {
        val s = sessions.getOrPut(sessionId) {
            State(sessionId, agentName).also {
                it.replay = replay
                it.roots = listOf(projectRoot)
                it.cwd = cwdOverride ?: ""
            }
        }
        s.agentName = agentName
        s.lastEventMillis = max(s.lastEventMillis, event.ts)
        when (event) {
            is AgentEvent.SessionStarted -> {
                s.replay = replay
                s.cwd = cwdOverride ?: event.cwd ?: s.cwd
                s.roots = listOfNotNull(projectRoot, event.cwd?.takeIf { cwdOverride != null && it.isNotBlank() }).distinct()
                if (event.configOptions.isNotEmpty() || !event.loaded) s.configOptions = event.configOptions
                if (event.mode != null) s.mode = event.mode
                if (!event.loaded) setActivity(s, ActivityKind.IDLE, "", null, null, false, null, event.ts)
            }
            is AgentEvent.TurnStarted -> {
                s.turnActive = true
                s.turnIn = event.promptChars.toLong()
                s.turnOut = 0
                s.costAtTurnStart = cost(s)
                s.textBuffer = StringBuilder()
                setActivity(s, ActivityKind.THINKING, event.promptPreview.preview(60), null, null, false, null, event.ts)
            }
            is AgentEvent.TurnEnded -> {
                val t = event.tokens
                if (t != null) {
                    s.anyReported = true
                    s.reportedIn += t.inputTokens
                    s.reportedOut += t.outputTokens // reasoning tokens are normally included in output
                    s.reportedCached += t.cachedReadTokens
                } else {
                    s.estIn += s.turnIn
                    s.estOut += s.turnOut
                }
                s.turnIn = 0
                s.turnOut = 0
                if (s.turnActive) s.turnCosts += (cost(s) - s.costAtTurnStart).coerceAtLeast(0.0)
                s.turnActive = false
                s.pendingPermission = null
                s.activityBeforePermission = null
                val title = event.stopReason?.takeIf { it != "end_turn" } ?: ""
                setActivity(s, ActivityKind.IDLE, title, null, null, event.stopReason == "error", null, event.ts)
            }
            is AgentEvent.Thought -> {
                s.turnOut += event.chars
                text(s, ActivityKind.THINKING, event.text, event.ts)
            }
            is AgentEvent.Message -> {
                s.turnOut += event.chars
                text(s, ActivityKind.MESSAGE, event.text, event.ts)
            }
            is AgentEvent.ToolStarted -> {
                s.turnOut += event.chars
                val loc = event.locations.firstOrNull()
                val path = loc?.path?.let { rel(s, it) }
                s.toolPaths[event.toolCallId] = path to loc?.line
                putToolCall(s, ToolCallDto(event.toolCallId, event.kind, event.title, event.status, path))
                event.locations.forEach { touch(s, rel(s, it.path)) }
                if (s.pendingPermission == null) {
                    setActivity(s, kindOf(event.kind), event.title, path, loc?.line, event.status == "failed", event.toolCallId, event.ts, force = true)
                }
            }
            is AgentEvent.ToolUpdated -> {
                s.turnIn += event.chars
                val prev = s.toolCalls[event.toolCallId]
                val loc = event.locations?.firstOrNull()
                val path = loc?.path?.let { rel(s, it) } ?: prev?.path
                if (loc != null) s.toolPaths[event.toolCallId] = path to loc.line
                event.locations?.forEach { touch(s, rel(s, it.path)) }
                val updated = ToolCallDto(
                    event.toolCallId,
                    event.kind ?: prev?.kind ?: "other",
                    event.title ?: prev?.title ?: "",
                    event.status ?: prev?.status ?: "pending",
                    path,
                )
                putToolCall(s, updated)
                val a = s.activity
                if (a.toolCallId == event.toolCallId) {
                    s.activity = a.copy(
                        kind = if (event.kind != null && a.kind != ActivityKind.WAITING_PERMISSION) kindOf(event.kind) else a.kind,
                        title = event.title ?: a.title,
                        path = path,
                        line = s.toolPaths[event.toolCallId]?.second ?: a.line,
                        failed = a.failed || event.status == "failed",
                    )
                } else if (prev == null && s.pendingPermission == null && event.status != "completed") {
                    // First sight of a tool call (agent skipped `tool_call`).
                    setActivity(s, kindOf(updated.kind), updated.title, path, loc?.line, event.status == "failed", event.toolCallId, event.ts, force = true)
                }
            }
            is AgentEvent.PlanUpdated -> {
                s.plan = event.entries
                val completed = event.entries.count { it.status == "completed" }
                if (event.entries.isEmpty()) {
                    s.planStartCost = null
                    s.completionMarks.clear()
                } else {
                    if (s.planStartCost == null || completed < s.completionMarks.size) {
                        s.planStartCost = cost(s)
                        s.completionMarks.clear()
                        repeat(completed) { s.completionMarks += cost(s) }
                    }
                    val now = cost(s)
                    while (s.completionMarks.size < completed) s.completionMarks += now
                }
            }
            is AgentEvent.PermissionRequested -> {
                val loc = event.locations.firstOrNull()
                val path = loc?.path?.let { rel(s, it) } ?: s.toolPaths[event.toolCallId]?.first
                s.pendingPermission = PermissionDto(event.toolCallId, event.title, event.options)
                if (s.activity.kind != ActivityKind.WAITING_PERMISSION) s.activityBeforePermission = s.activity
                setActivity(s, ActivityKind.WAITING_PERMISSION, event.title, path, loc?.line, false, event.toolCallId, event.ts, force = true)
            }
            is AgentEvent.PermissionResolved -> {
                s.pendingPermission = null
                val before = s.activityBeforePermission
                s.activityBeforePermission = null
                if (s.activity.kind == ActivityKind.WAITING_PERMISSION) {
                    val tc = s.toolCalls[event.toolCallId]
                    s.activity = when {
                        tc != null -> ActivityDto(kindOf(tc.kind), tc.title, tc.path, s.toolPaths[tc.toolCallId]?.second, false, tc.toolCallId, event.ts)
                        before != null -> before.copy(sinceMillis = event.ts)
                        else -> ActivityDto(ActivityKind.THINKING, "", null, null, false, null, event.ts)
                    }
                    if (event.outcome.startsWith("reject") || event.outcome == "cancelled") {
                        s.activity = s.activity.copy(kind = ActivityKind.THINKING, title = "Permission ${event.outcome}")
                    }
                }
            }
            is AgentEvent.UsageUpdated -> {
                if (event.used != null) s.contextUsed = event.used
                if (event.size != null) s.contextSize = event.size
                if (event.costAmount != null) {
                    s.reportedCost = event.costAmount
                    s.reportedCurrency = event.currency
                }
            }
            is AgentEvent.ConfigChanged -> {
                if (event.configOptions != null && event.configOptions.isNotEmpty()) {
                    val synthetic = s.configOptions.filter { old ->
                        old.id.startsWith("_") && event.configOptions.none { n ->
                            (AcpEventMapper.isModelOption(old) && AcpEventMapper.isModelOption(n)) ||
                                (AcpEventMapper.isModeOption(old) && AcpEventMapper.isModeOption(n))
                        }
                    }
                    s.configOptions = event.configOptions + synthetic
                }
                if (event.optionId != null && event.optionValue != null) {
                    s.configOptions = s.configOptions.map { if (it.id == event.optionId) it.copy(currentValue = event.optionValue) else it }
                }
                if (event.mode != null) s.mode = event.mode
            }
            is AgentEvent.FileHint -> {
                val path = event.path?.let { rel(s, it) }
                when (event.kind) {
                    HintKind.READ -> s.turnIn += event.chars
                    HintKind.EDIT -> s.turnOut += event.chars
                    HintKind.EXECUTE -> {}
                }
                if (event.kind != HintKind.EXECUTE) path?.let { touch(s, it) }
                // Only use the hint when no tool call already explains what the agent is doing.
                val a = s.activity
                val toolBusy = a.toolCallId != null && s.toolCalls[a.toolCallId]?.status.let { it == "pending" || it == "in_progress" }
                if (!toolBusy && s.pendingPermission == null && event.chars == 0) {
                    val kind = when (event.kind) {
                        HintKind.READ -> ActivityKind.READ
                        HintKind.EDIT -> ActivityKind.EDIT
                        HintKind.EXECUTE -> ActivityKind.EXECUTE
                    }
                    setActivity(s, kind, event.title, if (event.kind == HintKind.EXECUTE) null else path, event.line, false, null, event.ts, force = true)
                }
            }
            is AgentEvent.Custom -> {}
        }
    }

    /** Ends a session's activity when its tap disconnects. */
    fun disconnect(sessionId: String, ts: Long) {
        val s = sessions[sessionId] ?: return
        s.turnActive = false
        s.pendingPermission = null
        setActivity(s, ActivityKind.IDLE, "disconnected", null, null, false, null, ts)
    }

    fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun sessionIds(): Set<String> = sessions.keys.toSet()

    fun configOption(sessionId: String, configId: String): ConfigOption? =
        sessions[sessionId]?.configOptions?.firstOrNull { it.id == configId }

    fun snapshot(): List<SessionDto> = sessions.values.map(::toDto)

    // ---- internals -------------------------------------------------------------------------

    private fun text(s: State, kind: ActivityKind, chunk: String, ts: Long) {
        if (s.pendingPermission != null) return
        if (s.activity.kind != kind) s.textBuffer = StringBuilder()
        s.textBuffer.append(chunk)
        if (s.textBuffer.length > 400) s.textBuffer.delete(0, s.textBuffer.length - 200)
        val tail = s.textBuffer.toString().preview(1000).takeLast(80)
        if (s.activity.kind == kind) s.activity = s.activity.copy(title = tail)
        else setActivity(s, kind, tail, null, null, false, null, ts)
    }

    private fun setActivity(
        s: State, kind: ActivityKind, title: String, path: String?, line: Int?, failed: Boolean, toolCallId: String?, ts: Long,
        force: Boolean = false,
    ) {
        val a = s.activity
        if (!force && a.kind == kind && a.path == path && a.toolCallId == toolCallId) {
            s.activity = a.copy(title = title, failed = failed, line = line)
            return
        }
        s.activity = ActivityDto(kind, title, path, line, failed, toolCallId, ts)
    }

    private fun putToolCall(s: State, dto: ToolCallDto) {
        s.toolCalls[dto.toolCallId] = dto
        while (s.toolCalls.size > 20) s.toolCalls.remove(s.toolCalls.keys.first())
    }

    private fun touch(s: State, path: String) {
        if (path.isBlank()) return
        s.touched.remove(path)
        s.touched.add(path)
        while (s.touched.size > 50) s.touched.remove(s.touched.first())
    }

    private fun rel(s: State, path: String) = PathUtil.relativise(path, s.roots.ifEmpty { listOf(projectRoot) })

    private fun kindOf(kind: String): ActivityKind = when (kind.lowercase()) {
        "read" -> ActivityKind.READ
        "edit" -> ActivityKind.EDIT
        "delete" -> ActivityKind.DELETE
        "move" -> ActivityKind.MOVE
        "search" -> ActivityKind.SEARCH
        "execute" -> ActivityKind.EXECUTE
        "fetch" -> ActivityKind.FETCH
        "think" -> ActivityKind.THINKING
        else -> ActivityKind.OTHER
    }

    private fun tokens(s: State): Triple<Long, Long, Boolean> {
        val estIn = (s.estIn + s.turnIn) / 4
        val estOut = (s.estOut + s.turnOut) / 4
        val estimated = !s.anyReported || estIn + estOut > 0
        return Triple(s.reportedIn + estIn, s.reportedOut + estOut, estimated)
    }

    private fun modelOption(s: State): ConfigOption? =
        s.configOptions.firstOrNull { it.category.equals("model", true) }
            ?: s.configOptions.firstOrNull { AcpEventMapper.isModelOption(it) && !AcpEventMapper.isEffortOption(it) }

    private fun cost(s: State): Double {
        s.reportedCost?.let { return it }
        val (input, output, _) = tokens(s)
        val m = modelOption(s)
        val price = pricing.match(m?.currentValue)
            ?: pricing.lookup(m?.values?.firstOrNull { it.value == m.currentValue }?.name, s.agentName)
        return price?.cost(input, output, s.reportedCached) ?: 0.0
    }

    private fun projection(s: State, soFar: Double): Pair<Double?, Double?> {
        val total = s.plan.size
        val completed = s.plan.count { it.status == "completed" }
        if (total > 0 && completed > 0 && s.planStartCost != null && s.completionMarks.isNotEmpty()) {
            val remaining = total - completed
            if (remaining == 0) return soFar to soFar
            val start = s.planStartCost!!
            val increments = s.completionMarks.mapIndexed { i, c -> c - (if (i == 0) start else s.completionMarks[i - 1]) }
            val mean = max(0.0, (s.completionMarks.last() - start) / completed)
            val sd = max(stdDev(increments), mean * 0.25)
            return (soFar + remaining * max(0.0, mean - sd)) to (soFar + remaining * (mean + sd))
        }
        if (s.turnCosts.isEmpty()) return null to null
        if (!s.turnActive) return soFar to soFar
        val mean = s.turnCosts.average()
        val sd = max(stdDev(s.turnCosts), mean * 0.25)
        val spent = soFar - s.costAtTurnStart
        return (soFar + max(0.0, mean - sd - spent)) to (soFar + max(mean * 0.25, mean + sd - spent))
    }

    private fun stdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    private fun optionLabel(o: ConfigOption?): String? =
        o?.let { opt -> opt.values.firstOrNull { it.value == opt.currentValue }?.name ?: opt.currentValue.takeIf { it.isNotBlank() } }

    private fun toDto(s: State): SessionDto {
        val (input, output, estimated) = tokens(s)
        val soFar = cost(s)
        val (low, high) = projection(s, soFar)
        val modeOption = s.configOptions.firstOrNull { AcpEventMapper.isModeOption(it) }
        val mode = s.mode?.let { m -> modeOption?.values?.firstOrNull { it.value == m }?.name ?: m } ?: optionLabel(modeOption)
        return SessionDto(
            sessionId = s.sessionId,
            agentName = s.agentName,
            cwd = s.cwd,
            model = optionLabel(modelOption(s)),
            effort = optionLabel(modelOption(s).let { m -> s.configOptions.firstOrNull { it !== m && AcpEventMapper.isEffortOption(it) } }),
            mode = mode,
            turnActive = s.turnActive,
            activity = s.activity,
            usage = UsageDto(
                inputTokens = input,
                outputTokens = output,
                tokensEstimated = estimated,
                contextUsed = s.contextUsed,
                contextSize = s.contextSize,
                costSoFar = soFar,
                currency = s.reportedCurrency ?: pricing.currency,
                costEstimated = s.reportedCost == null,
                projectedCostLow = low,
                projectedCostHigh = high,
                alternativeCosts = pricing.alternativeCosts(input, output, s.reportedCached),
            ),
            plan = s.plan.map { PlanEntryDto(it.content, it.priority, it.status) },
            pendingPermission = s.pendingPermission,
            touchedFiles = s.touched.toList(),
            recentToolCalls = s.toolCalls.values.toList(),
            configOptions = s.configOptions.map { o ->
                ConfigOptionDto(o.id, o.name, o.category, o.currentValue, o.values.map { ConfigValueDto(it.value, it.name) })
            },
            lastEventMillis = s.lastEventMillis,
            replay = s.replay,
        )
    }
}
