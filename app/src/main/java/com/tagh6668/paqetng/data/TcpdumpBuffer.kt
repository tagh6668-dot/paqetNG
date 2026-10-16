package com.tagh6668.paqetng.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory buffer for tcpdump output lines (TCP packet info, flags, etc.).
 * Populated by [TcpdumpRunner] when VPN is connected.
 */
class TcpdumpBuffer(private val maxLines: Int = 5000) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val list = CopyOnWriteArrayList<String>()

    @Synchronized
    fun append(line: String) {
        list.add(line)
        while (list.size > maxLines) list.removeAt(0)
        _lines.value = list.toList()
    }

    /** Full text for export (newest last). */
    fun getFullText(separator: String = "\n"): String = list.joinToString(separator)

    /**
     * Returns the full "packet" block containing the line at [index].
     * tcpdump -v can output multiple lines per packet; lines that don't start with a timestamp
     * are continuation of the previous packet. This groups from the previous packet start up to
     * and including the next packet start (or end of buffer).
     */
    fun getPacketBlock(index: Int): String {
        val size = list.size
        if (index !in 0..<size) return list.getOrNull(index) ?: ""
        var start = index
        while (start > 0 && !isPacketStartLine(list[start - 1])) start--
        var end = index
        while (end < size - 1 && !isPacketStartLine(list[end + 1])) end++
        return list.subList(start, end + 1).joinToString("\n")
    }

    private fun isPacketStartLine(line: String): Boolean {
        return PACKET_TIMESTAMP_REGEX.containsMatchIn(line.trimStart())
    }

    @Synchronized
    fun clear() {
        list.clear()
        _lines.value = emptyList()
    }

    companion object {
        /** tcpdump line starts with time like "12:34:56.789012" or "12:34:56.789012 IP" */
        private val PACKET_TIMESTAMP_REGEX = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d+")
    }
}
