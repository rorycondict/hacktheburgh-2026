package com.example.tap

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun log(msg: String) {
    System.err.println("[acp-tap] $msg")
}

/**
 * Copies frames to every live plugin server registered in ~/.overworld/servers and to the raw log.
 * Never blocks the caller: every destination has a bounded queue drained by its own daemon thread.
 */
internal class Fanout(
    private val agentName: String,
    private val tapId: String,
    private val registryDir: File,
    logFile: File?,
    private val onInject: (String) -> Unit,
    private val onEmit: (String) -> Unit,
) {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val logQueue = ArrayBlockingQueue<String>(20_000)
    private val history = ArrayDeque<String>()
    private var historyBytes = 0L
    private val historyLock = Any()
    private var seq = 0L // guarded by historyLock
    @Volatile private var closed = false

    init {
        if (logFile != null) {
            daemon("acp-tap-log") {
                try {
                    logFile.parentFile?.mkdirs()
                    logFile.bufferedWriter(Charsets.UTF_8).use { w ->
                        while (true) {
                            val first = logQueue.poll(200, TimeUnit.MILLISECONDS)
                            if (first == null) {
                                if (closed) break
                                continue
                            }
                            w.write(first); w.write("\n")
                            while (true) {
                                val next = logQueue.poll() ?: break
                                w.write(next); w.write("\n")
                            }
                            w.flush()
                        }
                    }
                } catch (e: Exception) {
                    log("raw log disabled: ${e.message}")
                }
            }
        }
        daemon("acp-tap-registry") {
            while (!closed) {
                try {
                    scanRegistry()
                } catch (e: Exception) {
                    log("registry scan failed: ${e.message}")
                }
                Thread.sleep(1500)
            }
        }
    }

    fun publish(dir: String, line: String) {
        if (line.isBlank()) return
        synchronized(historyLock) {
            // Numbered under the lock so history, live sends and the log all agree on the order.
            val json = Json.frame(dir, System.currentTimeMillis(), line, ++seq)
            logQueue.offer(json)
            history.addLast(json)
            historyBytes += json.length
            while (history.size > 5000 || historyBytes > MAX_HISTORY_BYTES) {
                historyBytes -= history.removeFirst().length
            }
            // Enqueue while holding the history lock so a new connection sees each frame exactly once.
            for (c in connections.values) c.offer(json)
        }
    }

    /** Gives the queues a moment to drain before the process exits. */
    fun close(waitMillis: Long) {
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline &&
            (logQueue.isNotEmpty() || connections.values.any { it.pending() > 0 })) {
            Thread.sleep(20)
        }
        closed = true
        Thread.sleep(50)
        connections.values.forEach { it.close() }
    }

    private fun scanRegistry() {
        val files = registryDir.listFiles { f -> f.name.endsWith(".properties") } ?: emptyArray()
        val seen = HashSet<String>()
        for (f in files) {
            val key = f.name
            seen += key
            if (connections.containsKey(key)) continue
            val props = Properties()
            try {
                f.inputStream().use { props.load(it) }
            } catch (_: Exception) {
                continue
            }
            val port = props.getProperty("port")?.trim()?.toIntOrNull() ?: continue
            val token = props.getProperty("token")?.trim() ?: continue
            val pid = props.getProperty("pid")?.trim()?.toLongOrNull()
            if (pid != null && !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) continue
            connect(key, port, token)
        }
        // Drop connections whose registry file went away (IDE closed).
        for ((key, c) in connections) {
            if (key !in seen || c.dead) {
                c.close()
                connections.remove(key)
            }
        }
    }

    private fun connect(key: String, port: Int, token: String) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        val c = Connection(key, socket)
        c.offer(Json.hello(token, agentName, tapId, ProcessHandle.current().pid()))
        synchronized(historyLock) {
            for (h in history) c.offer(h)
            connections[key] = c
        }
        log("connected to plugin server on port $port")
    }

    private inner class Connection(val key: String, val socket: Socket) {
        private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
        @Volatile var dead = false

        init {
            daemon("acp-tap-send-$key") {
                try {
                    val w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    while (!dead) {
                        val first = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        w.write(first); w.write("\n")
                        while (true) {
                            val next = queue.poll() ?: break
                            w.write(next); w.write("\n")
                        }
                        w.flush()
                    }
                } catch (e: Exception) {
                    if (!dead) log("plugin server connection lost: ${e.message}")
                } finally {
                    close()
                }
            }
            daemon("acp-tap-recv-$key") {
                try {
                    val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (!dead) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("INJECT ")) {
                            onInject(line.substring("INJECT ".length))
                        } else if (line.startsWith("EMIT ")) {
                            onEmit(line.substring("EMIT ".length))
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                }
            }
        }

        fun offer(json: String) {
            if (!dead) queue.offer(json) // drop when full rather than block the agent
        }

        fun pending() = if (dead) 0 else queue.size

        fun close() {
            dead = true
            try { socket.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 20_000
        private const val MAX_HISTORY_BYTES = 16L * 1024 * 1024
    }
}

internal fun daemon(name: String, body: () -> Unit): Thread =
    Thread({
        try {
            body()
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            log("$name failed: $e")
        }
    }, name).apply {
        isDaemon = true
        start()
    }
