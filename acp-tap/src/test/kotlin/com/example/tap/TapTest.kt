package com.example.tap

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapTest {
    @Test
    fun splitterKeepsExactBytes() {
        val raw = "{\"a\":1}\r\n{\"b\":\"é\"}\n\npartial".toByteArray()
        val s = LineSplitter(ByteArrayInputStream(raw))
        val parts = generateSequence { s.next() }.toList()
        assertEquals(4, parts.size)
        assertContentEquals(raw, parts.fold(ByteArray(0)) { a, b -> a + b })
        assertNull(s.next())
    }

    @Test
    fun splitterHandlesLongLines() {
        val long = "x".repeat(300_000) + "\n" + "y\n"
        val s = LineSplitter(ByteArrayInputStream(long.toByteArray()))
        assertEquals(300_001, s.next()!!.size)
        assertEquals(2, s.next()!!.size)
    }

    @Test
    fun quoteEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.quote("a\"b\\c\n\u0001"))
    }

    @Test
    fun injectedResponsesAreRecognised() {
        assertTrue(Json.isInjectedResponse("""{"jsonrpc":"2.0","id":"floorplan-3","result":{}}"""))
        assertFalse(Json.isInjectedResponse("""{"jsonrpc":"2.0","id":3,"result":{"x":"floorplan-3"}}"""))
        assertFalse(Json.isInjectedResponse("""{"jsonrpc":"2.0","id":"floorplan-3","method":"x"}"""))
    }

    @Test
    fun parsesArgs() {
        assertEquals(TapArgs("Junie", listOf("junie", "--acp=true")), parseArgs(arrayOf("--name", "Junie", "--", "junie", "--acp=true")))
        assertNull(parseArgs(arrayOf("--name", "x", "--")))
    }
}
