package com.example.backend.world

import com.example.backend.llm.OpenAiClient
import com.example.backend.llm.OpenAiConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Room summaries. Heuristics are always available (in [WorldLayout]); when OPEN_AI_KEY is set this
 * asks an OpenAI model for one-liners in the background, cached in `<project>/.overworld/summaries.json`
 * keyed by git HEAD.
 */
@Service(Service.Level.PROJECT)
class SummaryService(private val project: Project, private val cs: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    @Volatile private var cache: Cached? = null
    private var job: Job? = null
    private var jobKey: String? = null
    private val failedKeys = HashSet<String>()

    private data class Cached(val key: String, val model: String, val rooms: Map<String, String>)

    private val cacheFile: File? get() = project.basePath?.let { File(File(it, ".overworld"), "summaries.json") }

    /** Latest known LLM summaries (possibly from an older revision; refreshed in the background). */
    fun current(): Summaries {
        val c = cache ?: load()?.also { cache = it }
        return Summaries(rooms = c?.rooms.orEmpty())
    }

    @Synchronized
    fun ensure(idx: IndexResult) {
        val config = OpenAiConfig.find(project) ?: return
        val roomIds = roomInputs(idx).keys
        if (roomIds.isEmpty()) return
        val key = cacheKey(idx)
        val c = cache ?: load()?.also { cache = it }
        if (c != null && c.key == key && roomIds.all { it in c.rooms }) return
        if (key in failedKeys || (job?.isActive == true && jobKey == key)) return
        job?.cancel()
        jobKey = key
        job = cs.launch(Dispatchers.IO) {
            try {
                val rooms = summarise(config, idx)
                if (rooms.isNotEmpty()) {
                    val updated = Cached(key, config.model, rooms)
                    cache = updated
                    save(updated)
                    project.service<WorldService>().summariesChanged()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LOG.warn("LLM summaries failed: ${e.message}")
                synchronized(this@SummaryService) { failedKeys += key }
            }
        }
    }

    private fun cacheKey(idx: IndexResult): String =
        idx.revision.ifBlank { "nogit-" + idx.files.map { it.path }.sorted().hashCode().toUInt().toString(16) }

    /** room id -> (name, files) */
    private fun roomInputs(idx: IndexResult): Map<String, List<FileInfo>> {
        val out = LinkedHashMap<String, List<FileInfo>>()
        out[WorldLayout.LOBBY_ID] = idx.files.filter { !it.path.contains('/') }
        idx.files.filter { it.path.contains('/') }.groupBy { it.path.substringBefore('/') }.toSortedMap().forEach { (k, v) -> out[k] = v }
        return out
    }

    private suspend fun summarise(config: OpenAiConfig, idx: IndexResult): Map<String, String> {
        val base = project.basePath ?: return emptyMap()
        val sb = StringBuilder()
        for ((id, files) in roomInputs(idx)) {
            sb.append("## room `").append(id).append("` (").append(if (id == WorldLayout.LOBBY_ID) "project root files" else "directory $id/")
                .append(", ").append(files.size).append(" files, ").append(files.sumOf { it.loc }).append(" lines)\n")
            val top = files.sortedByDescending { it.loc + it.churn * 20 }.take(15)
            for (f in top) sb.append("- ").append(f.path).append(" (").append(f.language.ifBlank { "?" }).append(", ").append(f.loc).append(" lines)\n")
            // A peek at the biggest source file helps the model name the purpose.
            top.firstOrNull { FileKinds.kind(it.path) == com.example.model.FurnitureKind.DESK }?.let { f ->
                val head = try { File(base, f.path).useLines { l -> l.take(25).joinToString("\n") } } catch (_: Exception) { "" }
                if (head.isNotBlank()) sb.append("```\n").append(head.take(1500)).append("\n```\n")
            }
            if (sb.length > 40_000) break
        }
        val system = "You label the parts of a software project for a map of the codebase. " +
            "Reply with a JSON object {\"rooms\": {\"<room id>\": \"<summary>\"}}. Each summary is one plain sentence fragment of at most 12 words " +
            "saying what that part of the code is for (e.g. \"Tool window UI and map renderer\"). No markdown."
        val answer = OpenAiClient(config).chat(system, sb.toString(), jsonMode = true)
        val root = Json.parseToJsonElement(answer) as? JsonObject ?: return emptyMap()
        val rooms = (root["rooms"] as? JsonObject) ?: root
        return rooms.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it.take(140) } }.toMap()
    }

    private fun load(): Cached? = try {
        val f = cacheFile
        if (f == null || !f.isFile) null
        else {
            val o = json.parseToJsonElement(f.readText()) as JsonObject
            Cached(
                key = (o["revision"] as? JsonPrimitive)?.contentOrNull ?: "",
                model = (o["model"] as? JsonPrimitive)?.contentOrNull ?: "",
                rooms = (o["rooms"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun save(c: Cached) {
        try {
            val f = cacheFile ?: return
            f.parentFile.mkdirs()
            val o = buildJsonObject {
                put("revision", c.key)
                put("model", c.model)
                put("generatedAtMillis", System.currentTimeMillis())
                putJsonObject("rooms") { c.rooms.toSortedMap().forEach { (k, v) -> put(k, v) } }
            }
            f.writeText(json.encodeToString(JsonObject.serializer(), o))
        } catch (e: Exception) {
            LOG.warn("Could not write summaries cache", e)
        }
    }

    companion object {
        private val LOG = logger<SummaryService>()
    }
}
