package com.example.backend.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpEventMapperTest {
    private fun sample(): List<TapLine.Frame> =
        javaClass.getResource("/sample-session.jsonl")!!.readText().lines().filter { it.isNotBlank() }.map { TapLine.parse(it) as TapLine.Frame }

    private fun from(line: String) = AcpEventMapper().map(Direction.FROM_AGENT, 1L, line)

    @Test
    fun sampleSessionMapsToExpectedEvents() {
        val mapper = AcpEventMapper()
        val events = sample().flatMap { mapper.map(it) }
        val kinds = events.map { it::class.simpleName }
        assertEquals(
            listOf(
                "SessionStarted", "TurnStarted", "Thought", "Thought", "PlanUpdated", "ToolStarted", "FileHint", "FileHint",
                "ToolUpdated", "UsageUpdated", "PlanUpdated", "ToolStarted", "PermissionRequested", "PermissionResolved",
                "FileHint", "ToolUpdated", "PlanUpdated", "ToolStarted", "ToolUpdated", "PlanUpdated", "Message", "Message",
                "UsageUpdated", "TurnEnded",
            ),
            kinds,
        )
        assertTrue(events.all { it.sessionId == "sess_demo_1" && it.schemaVersion == AGENT_EVENT_SCHEMA_VERSION })

        val started = assertIs<AgentEvent.SessionStarted>(events[0])
        assertEquals("/home/demo/hacktheburgh-2026", started.cwd)
        assertEquals("code", started.mode)
        val model = started.configOptions.single { it.id == "model" }
        assertEquals("gpt-5-mini", model.currentValue)
        assertEquals(listOf("gpt-5", "gpt-5-mini"), model.values.map { it.value })
        assertTrue(started.configOptions.any { AcpEventMapper.isEffortOption(it) && it.currentValue == "medium" })
        assertTrue(started.configOptions.any { it.id == AcpEventMapper.MODE_OPTION_ID }, "modes become a synthetic option")
        assertEquals("/home/demo/hacktheburgh-2026", mapper.sessionCwds["sess_demo_1"])

        val turn = assertIs<AgentEvent.TurnStarted>(events[1])
        assertTrue(turn.promptPreview.startsWith("Add a schemaVersion check"))

        val read = assertIs<AgentEvent.ToolStarted>(events[5])
        assertEquals("read", read.kind)
        assertEquals("call_1", read.toolCallId)
        assertTrue(read.locations.single().path.endsWith("acp/AcpEventMapper.kt"))
        assertEquals(1, read.locations.single().line)

        val readHint = assertIs<AgentEvent.FileHint>(events[6])
        assertEquals(HintKind.READ, readHint.kind)
        assertEquals(0, readHint.chars)
        val readResponse = assertIs<AgentEvent.FileHint>(events[7])
        assertTrue(readResponse.chars > 50, "the fs/read_text_file response is correlated by id")

        val usage = assertIs<AgentEvent.UsageUpdated>(events[9])
        assertEquals(18250L, usage.used)
        assertEquals(272000L, usage.size)
        assertNull(usage.costAmount)

        val perm = assertIs<AgentEvent.PermissionRequested>(events[12])
        assertEquals("call_2", perm.toolCallId)
        assertEquals(listOf("Allow", "Always allow", "Reject"), perm.options)
        assertEquals("allow_once", assertIs<AgentEvent.PermissionResolved>(events[13]).outcome)

        val write = assertIs<AgentEvent.FileHint>(events[14])
        assertEquals(HintKind.EDIT, write.kind)
        assertTrue(write.path!!.endsWith("AgentEvent.kt"))

        val ended = assertIs<AgentEvent.TurnEnded>(events.last())
        assertEquals("end_turn", ended.stopReason)
        assertEquals(ReportedTokens(24100, 2300, 12000, 0), ended.tokens)
    }

    @Test
    fun badInputNeverThrows() {
        val m = AcpEventMapper()
        for (line in listOf("", "not json", "[1,2]", "{", "{\"method\":5}", "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}",
            "{\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\"}}",
            "{\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"tool_call\"}}}",
            "{\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"something_new\",\"x\":1}}}",
            "{\"method\":\"some/unknown\",\"params\":{\"sessionId\":\"s\"}}")) {
            m.map(Direction.FROM_AGENT, 0, line)
            m.map(Direction.TO_AGENT, 0, line)
        }
        // A tool_call with no fields still yields a usable event.
        val ev = from("{\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"tool_call\"}}}").single()
        assertEquals("other", assertIs<AgentEvent.ToolStarted>(ev).kind)
        assertTrue(from("{\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"something_new\"}}}").isEmpty())
    }

    @Test
    fun customUpdatesPlanUpdateConfigAndMode() {
        val custom = from("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"_overworld_intent","text":"heading to auth"}}}""").single()
        assertEquals("_overworld_intent", assertIs<AgentEvent.Custom>(custom).name)

        val plan = from("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"plan_update","plan":{"entries":[{"content":"a","priority":"low","status":"completed"}]}}}}""").single()
        assertEquals(listOf(PlanEntry("a", "low", "completed")), assertIs<AgentEvent.PlanUpdated>(plan).entries)

        val cfg = from("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"config_option_update","configOptions":[{"id":"model","name":"Model","category":"model","currentValue":"b","options":[{"group":"g","name":"G","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]}]}]}}}""").single()
        val opt = assertIs<AgentEvent.ConfigChanged>(cfg).configOptions!!.single()
        assertEquals("b", opt.currentValue)
        assertEquals(listOf("a", "b"), opt.values.map { it.value }, "grouped options are flattened")

        val mode = from("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"current_mode_update","currentModeId":"ask"}}}""").single()
        assertEquals("ask", assertIs<AgentEvent.ConfigChanged>(mode).mode)

        val cost = from("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"usage_update","used":10,"size":100,"cost":{"amount":0.42,"currency":"USD"}}}}""").single()
        assertEquals(0.42, assertIs<AgentEvent.UsageUpdated>(cost).costAmount)
    }

    @Test
    fun modelsFieldAndInjectedSetConfigOption() {
        val m = AcpEventMapper()
        m.map(Direction.TO_AGENT, 0, """{"jsonrpc":"2.0","id":"n1","method":"session/new","params":{"cwd":"C:\\work\\proj"}}""")
        val started = m.map(Direction.FROM_AGENT, 0, """{"jsonrpc":"2.0","id":"n1","result":{"sessionId":"s2","models":{"currentModelId":"gpt-5","availableModels":[{"modelId":"gpt-5","name":"GPT-5"},{"modelId":"gpt-5-mini","name":"GPT-5 mini"}]}}}""")
            .single() as AgentEvent.SessionStarted
        assertEquals("C:\\work\\proj", started.cwd)
        assertEquals(AcpEventMapper.MODEL_OPTION_ID, started.configOptions.single().id)

        m.expectInjected("floorplan-1", "session/set_config_option", kotlinx.serialization.json.buildJsonObject {
            put("sessionId", kotlinx.serialization.json.JsonPrimitive("s2"))
            put("configId", kotlinx.serialization.json.JsonPrimitive("model"))
            put("value", kotlinx.serialization.json.JsonPrimitive("gpt-5-mini"))
        })
        val changed = m.map(Direction.FROM_AGENT, 0, """{"jsonrpc":"2.0","id":"floorplan-1","result":{}}""").single()
        changed as AgentEvent.ConfigChanged
        assertEquals("model", changed.optionId)
        assertEquals("gpt-5-mini", changed.optionValue)
        assertNotNull(m.sessionCwds["s2"])
    }

    @Test
    fun promptErrorEndsTurn() {
        val m = AcpEventMapper()
        m.map(Direction.TO_AGENT, 0, """{"jsonrpc":"2.0","id":7,"method":"session/prompt","params":{"sessionId":"s","prompt":[{"type":"text","text":"hi"}]}}""")
        val ev = m.map(Direction.FROM_AGENT, 0, """{"jsonrpc":"2.0","id":7,"error":{"code":-32603,"message":"boom"}}""").single()
        assertEquals("error", assertIs<AgentEvent.TurnEnded>(ev).stopReason)
    }

    @Test
    fun envelopeParsing() {
        val hello = TapLine.parse("""{"type":"hello","token":"t","agent":"Junie","tapId":"x","pid":12}""")
        assertEquals(TapLine.Hello("t", "Junie", "x", 12), hello)
        assertNull(TapLine.parse("""{"type":"frame","dir":"sideways","ts":1,"line":"{}"}"""))
        assertNull(TapLine.parse("garbage"))
    }
}
