package com.example.backend.tap

import com.example.backend.acp.AcpEventMapper
import com.example.backend.acp.AgentEvent
import com.example.backend.acp.TapLine
import com.example.backend.session.PathUtil
import com.example.backend.session.SessionService
import com.example.model.TapStatusDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<TapServer>()

/** Where taps and plugin servers meet. */
object OverworldDirs {
    val home: File get() = File(System.getProperty("user.home"), ".overworld")
    val servers: File get() = File(home, "servers")
    val logs: File get() = File(home, "logs")
}

/**
 * App-level TCP server on 127.0.0.1 that taps connect to. Each connection gets its own
 * [AcpEventMapper]; events are routed to the project whose base path contains the session cwd.
 */
@Service(Service.Level.APP)
class TapServer(private val cs: CoroutineScope) : Disposable {
    private val started = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString()
    private val serverId = UUID.randomUUID().toString()
    @Volatile private var serverSocket: ServerSocket? = null
    private val connections = ConcurrentHashMap.newKeySet<TapConnection>()
    private val injectIds = AtomicLong(0)

    val registryFile: File = File(OverworldDirs.servers, "$serverId.properties")
    val port: Int get() = serverSocket?.localPort ?: -1

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        try {
            val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
            serverSocket = ss
            writeRegistry(ss.localPort)
            LOG.info("Floorplan tap server listening on 127.0.0.1:${ss.localPort}")
            cs.launch(Dispatchers.IO) { acceptLoop(ss) }
        } catch (e: Exception) {
            LOG.warn("Could not start tap server", e)
            started.set(false)
        }
    }

    fun status(): TapStatusDto {
        ensureStarted()
        return TapStatusDto(port, registryFile.absolutePath, OverworldDirs.logs.absolutePath, connections.size)
    }

    /** Sends a JSON-RPC request to the agent behind [sessionId]'s tap. False if there is no live tap. */
    fun inject(sessionId: String, method: String, params: JsonObject): Boolean {
        val conn = connections.firstOrNull { sessionId in it.sessions.keys && !it.closed } ?: return false
        val id = "floorplan-${injectIds.incrementAndGet()}"
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        conn.registerInjected(id, method, params)
        return conn.send("INJECT $msg")
    }

    fun hasTap(sessionId: String): Boolean = connections.any { sessionId in it.sessions.keys && !it.closed }

    override fun dispose() {
        registryFile.delete()
        try { serverSocket?.close() } catch (_: Exception) {}
        connections.forEach { it.close() }
    }

    private fun writeRegistry(port: Int) {
        OverworldDirs.servers.mkdirs()
        val props = Properties()
        props["port"] = port.toString()
        props["token"] = token
        props["pid"] = ProcessHandle.current().pid().toString()
        registryFile.outputStream().use { props.store(it, "Floorplan plugin tap server") }
        registryFile.deleteOnExit()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (cs.isActive && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (!ss.isClosed) LOG.warn("accept failed", e)
                if (ss.isClosed) return
                delay(100)
                continue
            }
            cs.launch(Dispatchers.IO) { handle(socket) }
        }
    }

    private suspend fun handle(socket: Socket) {
        val conn = TapConnection(socket)
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            socket.soTimeout = 5000
            val hello = reader.readLine()?.let(TapLine::parse) as? TapLine.Hello
            if (hello == null || hello.token != token) {
                LOG.info("Rejected tap connection (bad hello)")
                return
            }
            socket.soTimeout = 0
            conn.agentName = hello.agent
            connections += conn
            LOG.info("Tap connected: agent=${hello.agent} tapId=${hello.tapId}")
            while (cs.isActive) {
                val line = reader.readLine() ?: break
                val frame = TapLine.parse(line) as? TapLine.Frame ?: continue
                val events = try {
                    synchronized(conn.mapper) { conn.mapper.map(frame) }
                } catch (e: Exception) {
                    LOG.debug("mapper failed", e); emptyList()
                }
                if (frame.line.contains("floorplan-")) conn.syncClientAfterInject(frame)
                for (ev in events) route(conn, ev)
            }
        } catch (e: Exception) {
            LOG.debug("tap connection ended", e)
        } finally {
            conn.close()
            connections -= conn
            for ((sessionId, project) in conn.sessions) {
                if (!project.isDisposed) project.service<SessionService>().disconnected(sessionId)
            }
        }
    }

    private fun route(conn: TapConnection, event: AgentEvent) {
        val project = conn.sessions[event.sessionId]?.takeIf { !it.isDisposed } ?: resolveProject(conn, event) ?: return
        conn.sessions[event.sessionId] = project
        project.service<SessionService>().apply(conn.agentName, event)
    }

    private fun resolveProject(conn: TapConnection, event: AgentEvent): Project? {
        val cwd = (event as? AgentEvent.SessionStarted)?.cwd ?: conn.mapper.sessionCwds[event.sessionId]
        return findProjectFor(cwd)
    }

    companion object {
        fun getInstance(): TapServer = service()

        /** The open project whose base path contains [cwd]; if cwd is unknown and one project is open, that one. */
        fun findProjectFor(cwd: String?): Project? {
            val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
            if (cwd.isNullOrBlank()) return projects.singleOrNull()
            return projects
                .filter { p -> p.basePath?.let { PathUtil.isUnder(cwd, it) } == true }
                .maxByOrNull { it.basePath!!.length }
        }
    }
}

/** One connected tap (one agent process). */
class TapConnection(private val socket: Socket) {
    val mapper = AcpEventMapper()
    @Volatile var agentName: String = "agent"
    @Volatile var closed = false
    /** Sessions seen on this connection and the project each was routed to. */
    val sessions = ConcurrentHashMap<String, Project>()
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
    private val injected = ConcurrentHashMap<String, Pair<String, String?>>()

    fun registerInjected(id: String, method: String, params: JsonObject) {
        injected[id] = method to (params["sessionId"] as? JsonPrimitive)?.content
        synchronized(mapper) { mapper.expectInjected(id, method, params) }
    }

    fun send(line: String): Boolean = try {
        synchronized(writer) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The tap hides responses to our injected requests from the client (Air). When such a response
     * carries new config options, tell Air through a standard `config_option_update` notification
     * (`EMIT` writes a line to the client's stdin) so its UI stays in sync.
     */
    fun syncClientAfterInject(frame: TapLine.Frame) {
        try {
            val msg = kotlinx.serialization.json.Json.parseToJsonElement(frame.line) as? JsonObject ?: return
            val id = (msg["id"] as? JsonPrimitive)?.content ?: return
            if (msg["method"] != null) return
            val (method, injectedSession) = injected.remove(id) ?: return
            if (method != "session/set_config_option") return
            val opts = (msg["result"] as? JsonObject)?.get("configOptions") ?: return
            val sessionId = injectedSession ?: return
            val note = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "session/update")
                put("params", buildJsonObject {
                    put("sessionId", sessionId)
                    put("update", buildJsonObject {
                        put("sessionUpdate", "config_option_update")
                        put("configOptions", opts)
                    })
                })
            }
            send("EMIT $note")
        } catch (_: Exception) {
        }
    }

    fun close() {
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }
}
