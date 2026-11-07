package com.example.backend.llm

import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** OpenAI settings from the environment or `<project>/.env` (OPEN_AI_KEY, optional OPEN_AI_MODEL). */
data class OpenAiConfig(val apiKey: String, val model: String) {
    companion object {
        const val DEFAULT_MODEL = "gpt-5-mini"

        fun find(project: Project?): OpenAiConfig? {
            val dotEnv = project?.basePath?.let { readDotEnv(File(it, ".env")) }.orEmpty()
            fun value(vararg keys: String) = keys.firstNotNullOfOrNull { k -> System.getenv(k)?.takeIf { it.isNotBlank() } ?: dotEnv[k]?.takeIf { it.isNotBlank() } }
            val key = value("OPEN_AI_KEY", "OPENAI_API_KEY") ?: return null
            return OpenAiConfig(key, value("OPEN_AI_MODEL", "OPENAI_MODEL") ?: DEFAULT_MODEL)
        }

        fun readDotEnv(file: File): Map<String, String> = try {
            if (!file.isFile) emptyMap()
            else file.readLines().mapNotNull { raw ->
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) return@mapNotNull null
                val k = line.substringBefore('=').trim()
                val v = line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
                k to v
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/**
 * Minimal Chat Completions client over java.net.http (no extra dependencies; Koog was not added,
 * see the backend report).
 */
class OpenAiClient(private val config: OpenAiConfig) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(system: String, user: String, jsonMode: Boolean = false): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
        val req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw IllegalStateException("OpenAI HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
        }
        val root = json.parseToJsonElement(resp.body()) as JsonObject
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val content = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
        content ?: throw IllegalStateException("OpenAI returned no content")
    }

}
