package com.example.backend.session

import com.example.backend.acp.double
import com.example.backend.acp.str
import com.example.backend.acp.strings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Prices in [Pricing.currency] per 1M tokens. */
data class ModelPrice(
    val id: String,
    val input: Double,
    val cachedInput: Double,
    val output: Double,
    val free: Boolean,
    val aliases: List<String>,
) {
    fun cost(inputTokens: Long, outputTokens: Long, cachedTokens: Long = 0): Double {
        val uncached = (inputTokens - cachedTokens).coerceAtLeast(0)
        return (uncached * input + cachedTokens.coerceAtMost(inputTokens) * cachedInput + outputTokens * output) / 1_000_000.0
    }
}

class Pricing(
    val currency: String,
    val models: List<ModelPrice>,
    val defaultModel: String?,
    val alternatives: List<String>,
) {
    /** Best price entry for a model id / display name, falling back to the agent name, then the default. */
    fun lookup(model: String?, agentName: String?): ModelPrice? =
        match(model) ?: (if (model.isNullOrBlank()) match(agentName) else null) ?: models.firstOrNull { it.id == defaultModel }

    fun match(text: String?): ModelPrice? {
        if (text.isNullOrBlank()) return null
        val t = normalise(text)
        models.firstOrNull { m -> normalise(m.id) == t || m.aliases.any { normalise(it) == t } }?.let { return it }
        // Longest id/alias contained in the text, e.g. "openai/gpt-5-mini (high)" -> gpt-5-mini.
        return models
            .flatMap { m -> (listOf(m.id) + m.aliases).map { normalise(it) to m } }
            .filter { (key, _) -> key.length >= 3 && t.contains(key) }
            .maxByOrNull { it.first.length }
            ?.second
    }

    /** Same tokens priced on each alternative (paid) model. */
    fun alternativeCosts(inputTokens: Long, outputTokens: Long, cachedTokens: Long): Map<String, Double> {
        val ids = alternatives.ifEmpty { models.filter { !it.free }.map { it.id } }
        val out = LinkedHashMap<String, Double>()
        for (id in ids) {
            val m = models.firstOrNull { it.id == id } ?: continue
            out[id] = m.cost(inputTokens, outputTokens, cachedTokens)
        }
        return out
    }

    companion object {
        private fun normalise(s: String) = s.lowercase().trim().replace(Regex("[\\s_]+"), "-")

        fun parse(text: String): Pricing {
            val root = Json.parseToJsonElement(text) as JsonObject
            val models = (root["models"] as? JsonObject).orEmpty().mapNotNull { (id, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                ModelPrice(
                    id = id,
                    input = o.double("input") ?: 0.0,
                    cachedInput = o.double("cachedInput") ?: o.double("input") ?: 0.0,
                    output = o.double("output") ?: 0.0,
                    free = (o["free"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    aliases = (o["aliases"] as? JsonArray).strings(),
                )
            }
            return Pricing(
                currency = root.str("currency") ?: "USD",
                models = models,
                defaultModel = root.str("default"),
                alternatives = (root["alternatives"] as? JsonArray).strings(),
            )
        }

        fun loadDefault(): Pricing {
            val text = Pricing::class.java.getResourceAsStream("/floorplan/pricing.json")?.use { it.readBytes().decodeToString() }
            return try {
                if (text != null) parse(text) else empty()
            } catch (_: Exception) {
                empty()
            }
        }

        fun empty() = Pricing("USD", emptyList(), null, emptyList())
    }
}
