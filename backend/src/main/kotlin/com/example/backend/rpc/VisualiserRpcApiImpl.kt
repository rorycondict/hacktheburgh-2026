package com.example.backend.rpc

import com.example.ModularPluginBackendBundle
import com.example.backend.acp.AcpEventMapper
import com.example.backend.llm.OpenAiClient
import com.example.backend.llm.OpenAiConfig
import com.example.backend.replay.ReplayService
import com.example.backend.session.PathUtil
import com.example.backend.session.SessionService
import com.example.backend.tap.TapServer
import com.example.backend.user.UserPositionService
import com.example.backend.world.FileKinds
import com.example.backend.world.WorldService
import com.example.backend.wrap.AcpJsonWrapper
import com.example.backend.wrap.AirAgents
import com.example.model.SessionDto
import com.example.model.TapStatusDto
import com.example.model.WorldDto
import com.example.model.WrapResultDto
import com.example.rpc.VisualiserRpcApi
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Path

class VisualiserRpcApiImpl : VisualiserRpcApi {

    override suspend fun world(projectId: ProjectId): Flow<WorldDto?> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        TapServer.getInstance().ensureStarted()
        return WorldService.getInstance(project).also { it.ensureStarted() }.world
    }

    override suspend fun sessions(projectId: ProjectId): Flow<List<SessionDto>> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        TapServer.getInstance().ensureStarted()
        return SessionService.getInstance(project).sessions
    }

    override suspend fun userPosition(projectId: ProjectId): Flow<String?> {
        val project = projectId.findProjectOrNull() ?: return emptyFlow()
        return UserPositionService.getInstance(project).also { it.ensureStarted() }.path
    }

    override suspend fun rebuildWorld(projectId: ProjectId) {
        val project = projectId.findProjectOrNull() ?: return
        WorldService.getInstance(project).also { it.ensureStarted() }.rebuild()
    }

    override suspend fun openFile(projectId: ProjectId, path: String, line: Int?) {
        val project = projectId.findProjectOrNull() ?: return
        val file = resolve(project, path) ?: return
        val vf = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file.toPath()) } ?: return
        withContext(Dispatchers.EDT) {
            val descriptor = if (line != null && line > 0) OpenFileDescriptor(project, vf, line - 1, 0) else OpenFileDescriptor(project, vf)
            descriptor.navigate(true)
        }
    }

    override suspend fun wrapAgents(): WrapResultDto = withContext(Dispatchers.IO) {
        val acpJson = File(File(System.getProperty("user.home"), ".jetbrains"), "acp.json")
        val javaExe = if (PathUtil.caseInsensitive) "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), javaExe).absolutePath
        val jar = tapJar() ?: return@withContext WrapResultDto(
            acpJson.absolutePath, emptyList(), emptyList(),
            "Could not locate the plugin's tap/acp-tap.jar; rebuild the plugin (prepareSandbox / buildPlugin).",
        )
        AcpJsonWrapper.wrapFile(acpJson, java, jar, " (" + ModularPluginBackendBundle.message("product.name") + ")", AirAgents.discover(listOf(PathManager.getSystemDir().toFile())))
    }

    override suspend fun startReplay(projectId: ProjectId, logFile: String?): String {
        val project = projectId.findProjectOrNull() ?: return "Project not found."
        return project.getService(ReplayService::class.java).start(logFile)
    }

    override suspend fun setConfigOption(projectId: ProjectId, sessionId: String, configId: String, value: String): Boolean {
        val project = projectId.findProjectOrNull() ?: return false
        val option = SessionService.getInstance(project).configOption(sessionId, configId) ?: return false
        if (option.values.isNotEmpty() && option.values.none { it.value == value }) return false
        val tap = TapServer.getInstance()
        if (!tap.hasTap(sessionId)) return false
        return when (configId) {
            AcpEventMapper.MODEL_OPTION_ID -> tap.inject(sessionId, "session/set_model", buildJsonObject { put("sessionId", sessionId); put("modelId", value) })
            AcpEventMapper.MODE_OPTION_ID -> tap.inject(sessionId, "session/set_mode", buildJsonObject { put("sessionId", sessionId); put("modeId", value) })
            else -> tap.inject(sessionId, "session/set_config_option", buildJsonObject {
                put("sessionId", sessionId); put("configId", configId); put("value", value)
            })
        }
    }

    override suspend fun ask(projectId: ProjectId, path: String, question: String): String {
        val project = projectId.findProjectOrNull() ?: return "Project not found."
        val config = OpenAiConfig.find(project)
            ?: return "Ask needs an OpenAI key: set OPEN_AI_KEY in the environment or in ${project.basePath ?: "<project>"}/.env, then try again."
        val target = resolve(project, path) ?: return "Can't find $path in this project."
        val context = withContext(Dispatchers.IO) { buildContext(project, target) }
        val system = "You are a concise senior engineer explaining a codebase. Answer in at most 6 short sentences or bullets, " +
            "based only on the code provided. If the code doesn't show the answer, say so."
        return try {
            OpenAiClient(config).chat(system, "Question: $question\n\n$context").trim()
        } catch (e: Exception) {
            LOG.warn("ask failed", e)
            "The model call failed: ${e.message}"
        }
    }

    override suspend fun status(): TapStatusDto = TapServer.getInstance().status()

    // ---- helpers ---------------------------------------------------------------------------

    private fun resolve(project: Project, path: String): File? {
        val base = project.basePath
        val f = if (PathUtil.isAbsolute(path) || base == null) File(path) else File(base, path)
        return f.takeIf { it.exists() }
    }

    private fun buildContext(project: Project, target: File): String {
        val base = project.basePath?.let(::File)
        fun rel(f: File) = base?.let { PathUtil.relativise(f.absolutePath, listOf(it.absolutePath)) } ?: f.path
        if (target.isFile) {
            val text = try { target.readText() } catch (_: Exception) { "" }
            return "File `${rel(target)}`:\n```\n${text.take(MAX_CONTEXT)}\n```"
        }
        val files = target.walkTopDown()
            .onEnter { d -> d == target || !(d.name.startsWith(".") || d.name in com.example.backend.world.ProjectIndexer.SKIP_DIRS) }
            .filter { it.isFile && FileKinds.isProbablyText(it.name) && it.length() < 512_000 }
            .take(400)
            .toList()
        val sb = StringBuilder("Directory `${rel(target)}` contains ${files.size} files:\n")
        files.take(150).forEach { sb.append("- ").append(rel(it)).append('\n') }
        for (f in files.sortedByDescending { it.length() }.take(6)) {
            if (sb.length > MAX_CONTEXT) break
            val head = try { f.useLines { it.take(80).joinToString("\n") } } catch (_: Exception) { continue }
            sb.append("\n`").append(rel(f)).append("` (first lines):\n```\n").append(head.take(6000)).append("\n```\n")
        }
        return sb.toString().take(MAX_CONTEXT)
    }

    private fun tapJar(): File? {
        val candidates = ArrayList<Path>()
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.let { candidates.add(it.resolve("tap").resolve("acp-tap.jar")) }
        try {
            // <plugin>/lib/modules/x.jar or <plugin>/lib/x.jar
            var dir: Path? = Path.of(VisualiserRpcApiImpl::class.java.protectionDomain.codeSource.location.toURI()).parent
            repeat(3) {
                dir?.let { candidates.add(it.resolve("tap").resolve("acp-tap.jar")) }
                dir = dir?.parent
            }
        } catch (_: Exception) {
        }
        return candidates.map { it.toFile() }.firstOrNull { it.isFile }
    }

    companion object {
        private const val PLUGIN_ID = "com.example.htb"
        private const val MAX_CONTEXT = 30_000
        private val LOG = logger<VisualiserRpcApiImpl>()
    }
}
