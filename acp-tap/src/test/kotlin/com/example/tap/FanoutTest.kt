package com.example.tap

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FanoutTest {
    @Test
    fun sendsHelloHistoryAndFramesAndAcceptsInject() {
        val dir = Files.createTempDirectory("tap-test").toFile()
        val registry = File(dir, "servers").apply { mkdirs() }
        val log = File(dir, "logs/x.jsonl")
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        File(registry, "a.properties").writeText(
            "port=${server.localPort}\ntoken=secret\npid=${ProcessHandle.current().pid()}\n"
        )
        // A dead server must be skipped without blocking anything.
        File(registry, "dead.properties").writeText("port=1\ntoken=x\npid=999999999\n")

        val injected = LinkedBlockingQueue<String>()
        val emitted = LinkedBlockingQueue<String>()
        val fanout = Fanout("Junie", "tap-1", registry, log, { injected += it }, { emitted += it })
        fanout.publish("to_agent", """{"jsonrpc":"2.0","id":1,"method":"session/new","params":{"cwd":"C:\\x"}}""")

        server.soTimeout = 10_000
        val socket = server.accept()
        val reader = socket.getInputStream().bufferedReader()
        val hello = reader.readLine()
        assertTrue(hello.contains("\"type\":\"hello\"") && hello.contains("\"token\":\"secret\"") && hello.contains("\"agent\":\"Junie\""), hello)
        val history = reader.readLine()
        assertTrue(history.contains("\"dir\":\"to_agent\"") && history.contains("session/new"), "frames from before the connection are replayed: $history")
        assertTrue(history.contains("\\\"cwd\\\":\\\"C:\\\\\\\\x\\\""), "line is embedded as an escaped JSON string: $history")

        fanout.publish("from_agent", """{"jsonrpc":"2.0","id":1,"result":{"sessionId":"s"}}""")
        val live = reader.readLine()
        assertTrue(live.contains("\"dir\":\"from_agent\""), live)

        socket.getOutputStream().write("INJECT {\"id\":\"floorplan-1\"}\nEMIT {\"method\":\"x\"}\n".toByteArray())
        socket.getOutputStream().flush()
        assertEquals("{\"id\":\"floorplan-1\"}", injected.poll(5, TimeUnit.SECONDS))
        assertEquals("{\"method\":\"x\"}", emitted.poll(5, TimeUnit.SECONDS))

        fanout.close(1000)
        socket.close()
        server.close()
        val logged = log.readLines()
        assertEquals(2, logged.size)
        assertNotNull(logged.firstOrNull { it.contains("session/new") })
        dir.deleteRecursively()
    }
}
