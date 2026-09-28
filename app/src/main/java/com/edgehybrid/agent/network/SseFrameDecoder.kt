package com.edgehybrid.agent.network

internal class SseFrameDecoder {
    private val data = StringBuilder()

    fun accept(line: String): String? {
        if (line.isEmpty()) {
            return consume()
        }

        if (line.startsWith(":")) {
            return null
        }

        val separatorIndex = line.indexOf(':')
        val field = if (separatorIndex >= 0) {
            line.substring(0, separatorIndex)
        } else {
            line
        }

        if (field != "data") {
            return null
        }

        var value = if (separatorIndex >= 0) {
            line.substring(separatorIndex + 1)
        } else {
            ""
        }

        if (value.startsWith(" ")) {
            value = value.substring(1)
        }

        if (data.isNotEmpty()) {
            data.append('\n')
        }
        data.append(value)
        return null
    }

    fun close(): String? = consume()

    private fun consume(): String? {
        if (data.isEmpty()) {
            return null
        }

        val frame = data.toString()
        data.setLength(0)
        return frame
    }
}