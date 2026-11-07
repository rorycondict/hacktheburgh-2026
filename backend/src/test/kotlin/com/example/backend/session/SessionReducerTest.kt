package com.example.backend.session

import com.example.backend.acp.AcpEventMapper
import com.example.backend.acp.AgentEvent
import com.example.backend.acp.ConfigOption
import com.example.backend.acp.ConfigValue
import com.example.backend.acp.HintKind
import com.example.backend.acp.PlanEntry
import com.example.backend.acp.TapLine
import com.example.model.ActivityKind
import com.example.model.SessionDto
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReducerTest {
    private val root = "/home/demo/hacktheburgh-2026"
    private val pricing = Pricing.loadDefault()

    private fun frames() = javaClass.getResource("/floorplan/sample-session.jsonl")!!.readText().lines()
        .filter { it.isNotBlank() }.map { TapLine.parse(it) as TapLine.Frame }

    private fun near(expected: Double, actual: Double?) =
        assertTrue(actual != null && abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun writeHintMovesCharacterToFile() {
        val r = SessionReducer(root, pricing)
        r.apply("a", AgentEvent.SessionStarted("s", 0, root, emptyList(), null, false))
        r.apply("a", AgentEvent.TurnStarted("s", 1, "go", 0))
        r.apply("a", AgentEvent.FileHint("s", 2, HintKind.EDIT, "$root/backend/Foo.kt", null, "Write Foo.kt", 500))
        val a = r.snapshot().single().activity
        assertEquals(ActivityKind.EDIT, a.kind)
        assertEquals("backend/Foo.kt", a.path)

        // A read's response hint only carries a token count; it must not replace the activity.
        r.apply("a", AgentEvent.FileHint("s", 3, HintKind.READ, "$root/docs/x.md", null, "Read x.md", 900, announce = false))
        assertEquals("backend/Foo.kt", r.snapshot().single().activity.path)
    }

    @Test
    fun repeatedToolUpdatesAreCountedOnce() {
        val r = SessionReducer(root, pricing)
        r.apply("a", AgentEvent.SessionStarted("s", 0, root, emptyList(), null, false))
        r.apply("a", AgentEvent.TurnStarted("s", 1, "go", 0))
        r.apply("a", AgentEvent.ToolStarted("s", 2, "t1", "edit", "Edit Foo.kt", "pending", emptyList(), 0))
        repeat(10) { i -> r.apply("a", AgentEvent.ToolUpdated("s", 3L + i, "t1", null, null, "in_progress", null, 4000)) }
        assertEquals(1000, r.snapshot().single().usage.inputTokens, "the same 4000-char payload ten times is still ~1000 tokens")
    }

    @Test
    fun pricingFileLoads() {
        assertTrue(pricing.models.size >= 5)
        assertEquals("gpt-5-mini", pricing.match("GPT-5 mini")?.id)
        assertEquals("gpt-5-mini", pricing.match("openai/gpt-5-mini")?.id)
        assertEquals("junie-lite", pricing.lookup(null, "Junie")?.id)
        assertEquals("gpt-5", pricing.lookup("some-unknown-model", "Codex")?.id, "falls back to the default")
    }

    @Test
    fun sampleSessionEndState() {
        val mapper = AcpEventMapper()
        val r = SessionReducer(root, pricing)
        val snapshots = ArrayList<SessionDto>()
        for (f in frames()) for (e in mapper.map(f)) {
            r.apply("Junie", e)
            snapshots += r.snapshot().single()
        }
        val s = snapshots.last()
        assertEquals("sess_demo_1", s.sessionId)
        assertEquals("Junie", s.agentName)
        assertEquals(root, s.cwd)
        assertEquals("GPT-5 mini", s.model)
        assertEquals("Medium", s.effort)
        assertEquals("Code", s.mode)
        assertFalse(s.turnActive)
        assertEquals(ActivityKind.IDLE, s.activity.kind)
        assertNull(s.pendingPermission)
        assertEquals(3, s.plan.size)
        assertTrue(s.plan.all { it.status == "completed" })
        assertEquals(
            listOf("backend/src/main/kotlin/com/example/backend/acp/AcpEventMapper.kt", "backend/src/main/kotlin/com/example/backend/acp/AgentEvent.kt"),
            s.touchedFiles,
        )
        assertEquals(listOf("call_1", "call_2", "call_3"), s.recentToolCalls.map { it.toolCallId })
        assertTrue(s.recentToolCalls.all { it.status == "completed" })

        // Reported usage wins over the estimate; cost is estimated from pricing.json (gpt-5-mini).
        assertEquals(24100, s.usage.inputTokens)
        assertEquals(2300, s.usage.outputTokens)
        assertFalse(s.usage.tokensEstimated)
        assertEquals(26480L, s.usage.contextUsed)
        assertEquals(272000L, s.usage.contextSize)
        assertTrue(s.usage.costEstimated)
        near(((24100 - 12000) * 0.25 + 12000 * 0.025 + 2300 * 2.0) / 1_000_000, s.usage.costSoFar)
        near(((24100 - 12000) * 1.25 + 12000 * 0.125 + 2300 * 10.0) / 1_000_000, s.usage.alternativeCosts["gpt-5"])
        assertEquals(s.usage.costSoFar, s.usage.projectedCostLow, "plan finished: projection collapses to the cost so far")

        // Intermediate states.
        val reading = snapshots.first { it.activity.kind == ActivityKind.READ }
        assertEquals("backend/src/main/kotlin/com/example/backend/acp/AcpEventMapper.kt", reading.activity.path)
        assertEquals(1, reading.activity.line)
        assertTrue(reading.usage.tokensEstimated)
        val waiting = snapshots.first { it.activity.kind == ActivityKind.WAITING_PERMISSION }
        assertEquals("Edit AgentEvent.kt", waiting.pendingPermission?.title)
        assertEquals("backend/src/main/kotlin/com/example/backend/acp/AgentEvent.kt", waiting.activity.path)
        val afterApproval = snapshots[snapshots.indexOf(waiting) + 1]
        assertEquals(ActivityKind.EDIT, afterApproval.activity.kind)
        assertNull(afterApproval.pendingPermission)
        assertTrue(snapshots.any { it.activity.kind == ActivityKind.EXECUTE })
        assertTrue(snapshots.any { it.activity.kind == ActivityKind.THINKING && it.turnActive })
    }

    @Test
    fun agentReportedCostWins() {
        val r = SessionReducer(root, pricing)
        r.apply("Codex", AgentEvent.SessionStarted("s", 0, root, emptyList(), null, false))
        r.apply("Codex", AgentEvent.TurnStarted("s", 1, "hi", 4000))
        r.apply("Codex", AgentEvent.UsageUpdated("s", 2, 1000, 200_000, 1.25, "USD"))
        val u = r.snapshot().single().usage
        assertEquals(1.25, u.costSoFar)
        assertFalse(u.costEstimated)
        assertTrue(u.tokensEstimated)
        assertEquals(1000, u.inputTokens, "4000 chars / 4")
    }

    @Test
    fun projectionFromPlanProgress() {
        // $1 per output token => cost == estimated output tokens == chars / 4.
        val p = Pricing("USD", listOf(ModelPrice("m", 0.0, 0.0, 1_000_000.0, false, emptyList())), "m", listOf("m"))
        val r = SessionReducer(root, p)
        val model = ConfigOption("model", "Model", "model", "m", listOf(ConfigValue("m", "M")))
        r.apply("a", AgentEvent.SessionStarted("s", 0, root, listOf(model), null, false))
        r.apply("a", AgentEvent.TurnStarted("s", 1, "go", 0))
        fun plan(done: Int) = AgentEvent.PlanUpdated("s", 2, List(4) { i -> PlanEntry("step $i", "medium", if (i < done) "completed" else "pending") })
        r.apply("a", plan(0))
        assertNull(r.snapshot().single().usage.projectedCostLow, "no completed entries and no finished turns yet")
        r.apply("a", AgentEvent.Message("s", 3, "x", 40))   // $10
        r.apply("a", plan(1))
        r.apply("a", AgentEvent.Message("s", 4, "x", 80))   // $20
        r.apply("a", plan(2))
        val u = r.snapshot().single().usage
        near(30.0, u.costSoFar)
        val sd = Math.sqrt(50.0) // stddev of [10, 20]
        near(30.0 + 2 * (15.0 - sd), u.projectedCostLow)
        near(30.0 + 2 * (15.0 + sd), u.projectedCostHigh)
        assertTrue(u.alternativeCosts.containsKey("m"))
    }

    @Test
    fun projectionFromTurnsWithoutPlan() {
        val p = Pricing("USD", listOf(ModelPrice("m", 0.0, 0.0, 1_000_000.0, false, emptyList())), "m", emptyList())
        val r = SessionReducer(root, p)
        r.apply("a", AgentEvent.SessionStarted("s", 0, root, emptyList(), null, false))
        r.apply("a", AgentEvent.TurnStarted("s", 1, "go", 0))
        r.apply("a", AgentEvent.Message("s", 2, "x", 40))
        r.apply("a", AgentEvent.TurnEnded("s", 3, "end_turn", null))
        val idle = r.snapshot().single().usage
        near(10.0, idle.projectedCostLow)
        r.apply("a", AgentEvent.TurnStarted("s", 4, "again", 0))
        val running = r.snapshot().single().usage
        assertNotNull(running.projectedCostHigh)
        assertTrue(running.projectedCostHigh!! > running.costSoFar)
        assertTrue(running.projectedCostLow!! <= running.projectedCostHigh!!)
    }

    @Test
    fun replayAliasesOriginalCwd() {
        val r = SessionReducer("C:/Users/me/proj", pricing)
        r.apply("a", AgentEvent.SessionStarted("s", 0, "/home/demo/other", emptyList(), null, false), replay = true, cwdOverride = "C:/Users/me/proj", sessionId = "replay:s")
        r.apply("a", AgentEvent.ToolStarted("s", 1, "t", "edit", "Edit", "pending", listOf(com.example.backend.acp.Location("/home/demo/other/src/A.kt", 3)), 0),
            replay = true, cwdOverride = "C:/Users/me/proj", sessionId = "replay:s")
        val s = r.snapshot().single()
        assertEquals("replay:s", s.sessionId)
        assertTrue(s.replay)
        assertEquals("src/A.kt", s.activity.path)
        assertEquals("C:/Users/me/proj", s.cwd)
    }

    @Test
    fun pathUtil() {
        assertEquals("src/A.kt", PathUtil.relativise("C:\\Users\\Me\\Proj\\src\\A.kt", listOf("c:/users/me/proj"), ignoreCase = true))
        assertEquals("C:/Other/A.kt", PathUtil.relativise("C:\\Other\\A.kt", listOf("c:/users/me/proj"), ignoreCase = true))
        assertEquals("src/A.kt", PathUtil.relativise("./src/A.kt", listOf("/x")))
        assertTrue(PathUtil.isUnder("C:\\Users\\Me\\Proj\\sub", "c:/users/me/proj", ignoreCase = true))
        assertFalse(PathUtil.isUnder("C:/Users/Me/Project2", "C:/Users/Me/Proj", ignoreCase = true))
    }
}
