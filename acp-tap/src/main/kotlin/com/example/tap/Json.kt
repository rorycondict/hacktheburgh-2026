package com.example.tap

/** Minimal JSON helpers: the tap only ever writes small envelopes around raw lines. */
internal object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** [seq] increases by one per frame for the tap's lifetime, so a server can skip frames replayed on reconnect. */
    fun frame(dir: String, ts: Long, line: String, seq: Long): String =
        "{\"type\":\"frame\",\"seq\":$seq,\"dir\":\"$dir\",\"ts\":$ts,\"line\":${quote(line)}}"

    fun hello(token: String, agent: String, tapId: String, pid: Long): String =
        "{\"type\":\"hello\",\"token\":${quote(token)},\"agent\":${quote(agent)},\"tapId\":${quote(tapId)},\"pid\":$pid}"

    private val floorplanId = Regex("\"id\"\\s*:\\s*\"floorplan-")
    private val methodKey = Regex("\"method\"\\s*:")

    /** True for a JSON-RPC response (no method) to a request the plugin injected. */
    fun isInjectedResponse(line: String): Boolean =
        line.contains("floorplan-") && floorplanId.containsMatchIn(line) && !methodKey.containsMatchIn(line)
}
