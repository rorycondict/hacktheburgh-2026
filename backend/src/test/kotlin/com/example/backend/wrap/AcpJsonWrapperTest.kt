package com.example.backend.wrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpJsonWrapperTest {
    private companion object { const val SUFFIX = " (Test)" }

    private val input = Json.parseToJsonElement(
        """{"agent_servers":{"Junie":{"command":"junie","args":["--acp=true"],"env":{"JUNIE_KEY":"x"}}},"other":1}"""
    ) as JsonObject

    @Test
    fun wrapsOnceAndKeepsOriginals() {
        val first = AcpJsonWrapper.wrap(input, "/jbr/bin/java", "/plugin/tap/acp-tap.jar", SUFFIX)
        assertEquals(listOf("Junie"), first.wrapped)
        val servers = first.root["agent_servers"] as JsonObject
        assertEquals(setOf("Junie", "Junie" + SUFFIX), servers.keys)
        val w = servers["Junie" + SUFFIX] as JsonObject
        assertEquals(JsonPrimitive("/jbr/bin/java"), w["command"])
        assertEquals(
            listOf("-jar", "/plugin/tap/acp-tap.jar", "--name", "Junie", "--", "junie", "--acp=true"),
            (w["args"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(input["agent_servers"]!!.let { (it as JsonObject)["Junie"] }, servers["Junie"])
        assertEquals(Json.parseToJsonElement("""{"JUNIE_KEY":"x"}"""), w["env"])
        assertEquals(JsonPrimitive(1), first.root["other"])

        val second = AcpJsonWrapper.wrap(first.root, "/jbr/bin/java", "/plugin/tap/acp-tap.jar", SUFFIX)
        assertTrue(second.wrapped.isEmpty())
        assertEquals(listOf("Junie"), second.alreadyWrapped)
        assertEquals(2, (second.root["agent_servers"] as JsonObject).size, "never double-wraps")
    }

    @Test
    fun airAgentsReplaceClashingCustomWrap() {
        val air = listOf(AirAgents.Agent("acp.registry.junie", "Junie", "/air/junie/junie.exe", listOf("--acp=true"), mapOf("K" to "v")))
        val out = AcpJsonWrapper.wrap(input, "java", "tap.jar", SUFFIX, air)
        assertEquals(listOf("Junie"), out.wrapped)
        val w = (out.root["agent_servers"] as JsonObject)["Junie" + SUFFIX] as JsonObject
        assertEquals(
            listOf("-jar", "tap.jar", "--name", "Junie", "--", "/air/junie/junie.exe", "--acp=true"),
            (w["args"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(Json.parseToJsonElement("""{"K":"v"}"""), w["env"])
    }

    @Test
    fun emptyFile() {
        val out = AcpJsonWrapper.wrap(JsonObject(emptyMap()), "java", "tap.jar", SUFFIX)
        assertEquals(0, out.originals)
        assertTrue(out.wrapped.isEmpty())
    }
}
