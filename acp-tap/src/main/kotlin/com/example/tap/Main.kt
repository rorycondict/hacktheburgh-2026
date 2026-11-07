package com.example.tap

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.system.exitProcess

/**
 * ACP tap: `java -jar acp-tap.jar --name <agent> -- <command> <args...>`
 *
 * Spawns the real agent, forwards stdin/stdout byte for byte (flushed per line), and copies each
 * JSON-RPC line to the plugin servers in ~/.overworld/servers and to ~/.overworld/logs.
 * Forward first, copy second: nothing here may block or alter the protocol stream.
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    if (parsed == null) {
        log("usage: java -jar acp-tap.jar --name <agent> -- <command> [args...]")
        exitProcess(2)
    }
    val (agentName, command) = parsed
    exitProcess(Tap(agentName, command).run())
}

internal data class TapArgs(val name: String, val command: List<String>)

internal fun parseArgs(args: Array<String>): TapArgs? {
    var name: String? = System.getenv("OVERWORLD_AGENT_NAME")?.takeIf { it.isNotBlank() }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                val cmd = args.drop(i + 1)
                if (cmd.isEmpty()) return null
                return TapArgs(name ?: File(cmd[0]).nameWithoutExtension, cmd)
            }
            a == "--name" && i + 1 < args.size -> { name = args[i + 1]; i++ }
            a.startsWith("--name=") -> name = a.substringAfter('=')
            else -> {
                // No "--": treat the rest as the command.
                val cmd = args.drop(i)
                return TapArgs(name ?: File(cmd[0]).nameWithoutExtension, cmd)
            }
        }
        i++
    }
    return null
}

internal class Tap(private val agentName: String, private val command: List<String>) {
    private val home = File(System.getProperty("user.home"), ".overworld")
    private val tapId = UUID.randomUUID().toString()

    fun run(): Int {
        val process = try {
            start(command)
        } catch (e: IOException) {
            log("failed to start agent ${command.joinToString(" ")}: ${e.message}")
            return 127
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            if (process.isAlive) {
                process.descendants().forEach { it.destroy() }
                process.destroy()
            }
        })

        val agentIn = process.outputStream
        val agentInLock = Any()
        val out: OutputStream = System.out
        val outLock = Any()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val safeName = agentName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
        val fanout = Fanout(
            agentName = agentName,
            tapId = tapId,
            registryDir = File(home, "servers"),
            logFile = File(File(home, "logs"), "$stamp-$safeName.jsonl"),
            onInject = { line ->
                try {
                    synchronized(agentInLock) {
                        agentIn.write(line.toByteArray(Charsets.UTF_8))
                        agentIn.write('\n'.code)
                        agentIn.flush()
                    }
                } catch (e: IOException) {
                    log("inject failed: ${e.message}")
                }
            },
            onEmit = { line ->
                // A notification the plugin wants the client to see (e.g. config_option_update after an inject).
                try {
                    synchronized(outLock) {
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.write('\n'.code)
                        out.flush()
                    }
                } catch (e: IOException) {
                    log("emit failed: ${e.message}")
                }
            },
        )

        // Client (Air) -> agent.
        daemon("acp-tap-stdin") {
            val splitter = LineSplitter(System.`in`)
            try {
                while (true) {
                    val bytes = splitter.next() ?: break
                    synchronized(agentInLock) {
                        agentIn.write(bytes)
                        agentIn.flush()
                    }
                    fanout.publish("to_agent", decode(bytes))
                }
            } catch (e: IOException) {
                log("stdin forwarding stopped: ${e.message}")
            } finally {
                try { synchronized(agentInLock) { agentIn.close() } } catch (_: IOException) {}
            }
        }

        // Agent -> client (Air). Runs on the main thread.
        val splitter = LineSplitter(process.inputStream)
        try {
            while (true) {
                val bytes = splitter.next() ?: break
                val line = decode(bytes)
                if (!Json.isInjectedResponse(line)) {
                    synchronized(outLock) {
                        out.write(bytes)
                        out.flush()
                    }
                }
                fanout.publish("from_agent", line)
            }
        } catch (e: IOException) {
            log("stdout forwarding stopped: ${e.message}")
        }

        val code = try { process.waitFor() } catch (_: InterruptedException) { 1 }
        fanout.close(500)
        return code
    }

    private fun start(cmd: List<String>): Process {
        val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT)
        return try {
            pb.start()
        } catch (e: IOException) {
            // Windows: `junie`, `npx` etc. are often .cmd/.bat shims that CreateProcess cannot run.
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                log("retrying through cmd.exe: ${e.message}")
                ProcessBuilder(listOf("cmd.exe", "/c") + cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            } else {
                throw e
            }
        }
    }

    private fun decode(bytes: ByteArray): String {
        var end = bytes.size
        while (end > 0 && (bytes[end - 1] == '\n'.code.toByte() || bytes[end - 1] == '\r'.code.toByte())) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}

/** Splits a stream into lines, keeping the exact bytes (including the trailing '\n'). */
internal class LineSplitter(private val input: InputStream) {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var len = 0
    private var eof = false

    fun next(): ByteArray? {
        var acc: ByteArrayOutputStream? = null
        while (true) {
            if (pos >= len) {
                if (eof) return acc?.takeIf { it.size() > 0 }?.toByteArray()
                len = input.read(buf)
                pos = 0
                if (len <= 0) {
                    len = 0
                    eof = true
                    continue
                }
            }
            var i = pos
            while (i < len && buf[i] != '\n'.code.toByte()) i++
            if (i < len) {
                val end = i + 1
                return if (acc == null) {
                    buf.copyOfRange(pos, end).also { pos = end }
                } else {
                    acc.write(buf, pos, end - pos); pos = end; acc.toByteArray()
                }
            }
            if (acc == null) acc = ByteArrayOutputStream(maxOf(256, (len - pos) * 2))
            acc.write(buf, pos, len - pos)
            pos = len
        }
    }
}
