package com.example.backend.wrap

import com.example.model.WrapResultDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Adds tap-wrapped copies of the agents in `~/.jetbrains/acp.json`. */
object AcpJsonWrapper {
    const val SUFFIX = " (Floorplan)"
    private const val SERVERS = "agent_servers"

    data class Outcome(val root: JsonObject, val wrapped: List<String>, val alreadyWrapped: List<String>, val originals: Int)

    /** Pure transformation; keeps every original entry and any unknown fields. */
    fun wrap(root: JsonObject, javaPath: String, tapJar: String): Outcome {
        val servers = (root[SERVERS] as? JsonObject) ?: JsonObject(emptyMap())
        val result = LinkedHashMap<String, JsonElement>(servers)
        val wrapped = ArrayList<String>()
        val already = ArrayList<String>()
        var originals = 0
        for ((name, entry) in servers) {
            val o = entry as? JsonObject ?: continue
            if (isWrapped(name, o)) continue
            val command = (o["command"] as? JsonPrimitive)?.contentOrNull ?: continue
            originals++
            val args = (o["args"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val wrappedName = name + SUFFIX
            val newEntry = LinkedHashMap<String, JsonElement>(o)
            newEntry["command"] = JsonPrimitive(javaPath)
            newEntry["args"] = JsonArray((listOf("-jar", tapJar, "--name", name, "--", command) + args).map(::JsonPrimitive))
            if (servers.containsKey(wrappedName)) already += name else wrapped += name
            // Refresh existing wrapped copies too (the tap jar path may have moved), never wrap twice.
            result[wrappedName] = JsonObject(newEntry)
        }
        val newRoot = LinkedHashMap<String, JsonElement>(root)
        newRoot[SERVERS] = JsonObject(result)
        return Outcome(JsonObject(newRoot), wrapped, already, originals)
    }

    fun isWrapped(name: String, entry: JsonObject): Boolean =
        name.endsWith(SUFFIX) || (entry["args"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull?.endsWith("acp-tap.jar") == true } == true

    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Reads, backs up, rewrites [acpJson]. */
    fun wrapFile(acpJson: File, javaPath: String, tapJar: File): WrapResultDto {
        val path = acpJson.absolutePath
        if (!tapJar.isFile) {
            return WrapResultDto(path, emptyList(), emptyList(), "Tap jar not found at ${tapJar.absolutePath}; rebuild the plugin (prepareSandbox / buildPlugin).")
        }
        val existed = acpJson.isFile
        val root = try {
            if (existed) Json.parseToJsonElement(acpJson.readText()) as? JsonObject
                ?: return WrapResultDto(path, emptyList(), emptyList(), "acp.json is not a JSON object; left unchanged.")
            else JsonObject(emptyMap())
        } catch (e: Exception) {
            return WrapResultDto(path, emptyList(), emptyList(), "Could not parse acp.json (${e.message}); left unchanged.")
        }
        val out = wrap(root, javaPath, tapJar.absolutePath)
        try {
            acpJson.parentFile?.mkdirs()
            val bak = File(acpJson.parentFile, "acp.json.bak")
            val hadWrapped = (root[SERVERS] as? JsonObject).orEmpty().any { (n, e) -> e is JsonObject && isWrapped(n, e) }
            // Keep the pristine backup from the first wrap; refresh it only while the file is unwrapped.
            if (existed && (!bak.exists() || !hadWrapped)) acpJson.copyTo(bak, overwrite = true)
            if (existed || out.originals > 0) acpJson.writeText(pretty.encodeToString(JsonObject.serializer(), out.root) + "\n")
            else acpJson.writeText(pretty.encodeToString(JsonObject.serializer(), JsonObject(mapOf(SERVERS to JsonObject(emptyMap())))) + "\n")
        } catch (e: Exception) {
            return WrapResultDto(path, emptyList(), emptyList(), "Could not write acp.json: ${e.message}")
        }
        val message = when {
            out.originals == 0 -> "No custom agents in $path yet. Add one under \"agent_servers\" (e.g. \"Junie\": {\"command\": \"junie\", \"args\": [\"--acp=true\"]}) and run Wrap again."
            out.wrapped.isEmpty() -> "All ${out.alreadyWrapped.size} agent(s) were already wrapped (paths refreshed). Pick the \"…$SUFFIX\" entries in Air."
            else -> "Wrapped ${out.wrapped.joinToString()}. Restart Air / the IDE, then pick the \"…$SUFFIX\" entries. Backup: acp.json.bak"
        }
        return WrapResultDto(path, out.wrapped, out.alreadyWrapped, message)
    }
}
