package com.subflow.input

import com.subflow.models.TorrentFile

// reads file names and sizes out of .torrent bencode, no video download needed
object TorrentParser {

    data class TorrentInfo(
        val name: String,
        val files: List<TorrentFile>
    )

    fun parse(bytes: ByteArray): TorrentInfo? {
        return try {
            val decoder = Bencode(bytes)
            val root = decoder.decode() as? Map<*, *> ?: return null
            val info = root["info"] as? Map<*, *> ?: return null
            val name = (info["name"] as? ByteArray)?.toString(Charsets.UTF_8) ?: "torrent"
            val files = mutableListOf<TorrentFile>()
            val fileList = info["files"] as? List<*>
            if (fileList != null) {
                for (f in fileList) {
                    val fm = f as? Map<*, *> ?: continue
                    val length = fm["length"] as? Long ?: continue
                    val pathParts = (fm["path"] as? List<*>)?.mapNotNull {
                        (it as? ByteArray)?.toString(Charsets.UTF_8)
                    } ?: continue
                    files += TorrentFile(pathParts.joinToString("/"), length)
                }
            } else {
                val length = info["length"] as? Long ?: 0L
                files += TorrentFile(name, length)
            }
            TorrentInfo(name, files)
        } catch (e: Exception) {
            null
        }
    }

    /** Minimal bencode decoder */
    private class Bencode(private val data: ByteArray) {
        private var pos = 0

        fun decode(): Any? = readValue()

        private fun readValue(): Any? {
            if (pos >= data.size) return null
            return when (val c = data[pos].toInt().toChar()) {
                'i' -> readInt()
                'l' -> readList()
                'd' -> readDict()
                in '0'..'9' -> readBytes()
                else -> throw IllegalArgumentException("bencode: unexpected '$c' at $pos")
            }
        }

        private fun readInt(): Long {
            pos++ // 'i'
            val end = indexOf('e')
            val v = String(data, pos, end - pos).toLong()
            pos = end + 1
            return v
        }

        private fun readBytes(): ByteArray {
            val colon = indexOf(':')
            val len = String(data, pos, colon - pos).toInt()
            val start = colon + 1
            val out = data.copyOfRange(start, start + len)
            pos = start + len
            return out
        }

        private fun readList(): List<Any?> {
            pos++ // 'l'
            val list = mutableListOf<Any?>()
            while (data[pos].toInt().toChar() != 'e') list += readValue()
            pos++
            return list
        }

        private fun readDict(): Map<String, Any?> {
            pos++ // 'd'
            val map = LinkedHashMap<String, Any?>()
            while (data[pos].toInt().toChar() != 'e') {
                val key = readBytes().toString(Charsets.UTF_8)
                map[key] = readValue()
            }
            pos++
            return map
        }

        private fun indexOf(c: Char): Int {
            var i = pos
            while (i < data.size && data[i].toInt().toChar() != c) i++
            return i
        }
    }
}
