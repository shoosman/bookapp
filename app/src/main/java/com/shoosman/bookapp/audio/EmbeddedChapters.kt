package com.shoosman.bookapp.audio

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Главы, зашитые в сам аудиофайл:
 *  - M4B/M4A: дорожка глав QuickTime и таблица глав Nero (chpl);
 *  - MP3: кадры CHAP в теге ID3v2.
 */
object EmbeddedChapters {

    class Mark(val startMs: Long, val title: String)

    fun read(ch: FileChannel): List<Mark> = runCatching {
        val head = readAt(ch, 0, 12)
        when {
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(ch)
            head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> id3(ch)
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).sortedBy { it.startMs }

    // ---------------- MP4 ----------------

    private class Box(val type: String, val start: Int, val end: Int)

    private fun mp4(ch: FileChannel): List<Mark> {
        val moov = findMoov(ch) ?: return emptyList()
        val qt = runCatching { quickTime(moov, ch) }.getOrDefault(emptyList())
        if (qt.size >= 2) return qt
        val nero = runCatching { chpl(moov) }.getOrDefault(emptyList())
        return if (nero.size >= qt.size) nero else qt
    }

    private fun findMoov(ch: FileChannel): ByteArray? {
        val len = ch.size()
        var p = 0L
        while (p + 8 <= len) {
            val h = readAt(ch, p, 16)
            if (h.size < 8) return null
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                size = u64(h, 8)
                hdr = 16
            } else if (size == 0L) {
                size = len - p
            }
            if (size < hdr) return null
            if (type == "moov") {
                if (size > 64L shl 20) return null
                return readAt(ch, p, size.toInt())
            }
            p += size
        }
        return null
    }

    private fun boxes(d: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(d, p)
            val type = String(d, p + 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                size = u64(d, p + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < hdr || p + size > end) break
            out.add(Box(type, p + hdr, (p + size).toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun child(d: ByteArray, start: Int, end: Int, type: String): Box? =
        boxes(d, start, end).firstOrNull { it.type == type }

    private fun path(d: ByteArray, box: Box?, vararg types: String): Box? {
        var cur = box ?: return null
        for (t in types) cur = child(d, cur.start, cur.end, t) ?: return null
        return cur
    }

    /** i-е 32-битное поле после version/flags «полного» бокса. */
    private fun field(d: ByteArray, box: Box, i: Int): Long = u32(d, box.start + 4 + 4 * i)

    private fun chpl(moov: ByteArray): List<Mark> {
        val root = Box("", 0, moov.size)
        val b = path(moov, root, "moov", "udta", "chpl") ?: return emptyList()
        val version = moov[b.start].toInt() and 0xFF
        var q = b.start + 4 + if (version == 1) 4 else 1
        val count = moov[q].toInt() and 0xFF
        q += 1
        val out = ArrayList<Mark>()
        repeat(count) {
            if (q + 9 > b.end) return out
            val start100ns = u64(moov, q)
            val len = moov[q + 8].toInt() and 0xFF
            q += 9
            val title = String(moov, q, minOf(len, b.end - q), Charsets.UTF_8)
            q += len
            out.add(Mark(start100ns / 10_000, title.trim()))
        }
        return out
    }

    private fun quickTime(moov: ByteArray, ch: FileChannel): List<Mark> {
        val m = child(moov, 0, moov.size, "moov") ?: return emptyList()
        val traks = boxes(moov, m.start, m.end).filter { it.type == "trak" }

        fun trackId(t: Box): Long {
            val tk = child(moov, t.start, t.end, "tkhd") ?: return -1
            val version = moov[tk.start].toInt() and 0xFF
            return u32(moov, tk.start + 4 + if (version == 1) 16 else 8)
        }

        val chapterIds = HashSet<Long>()
        for (t in traks) {
            val ref = path(moov, t, "tref", "chap") ?: continue
            var i = ref.start
            while (i + 4 <= ref.end) {
                chapterIds.add(u32(moov, i))
                i += 4
            }
        }
        val track = traks.firstOrNull { trackId(it) in chapterIds } ?: return emptyList()

        val mdhd = path(moov, track, "mdia", "mdhd") ?: return emptyList()
        val mdhdVersion = moov[mdhd.start].toInt() and 0xFF
        val timescale = u32(moov, mdhd.start + 4 + if (mdhdVersion == 1) 16 else 8).coerceAtLeast(1)
        val stbl = path(moov, track, "mdia", "minf", "stbl") ?: return emptyList()

        // Время начала каждого сэмпла
        val stts = child(moov, stbl.start, stbl.end, "stts") ?: return emptyList()
        val times = ArrayList<Long>()
        var t = 0L
        for (i in 0 until field(moov, stts, 0).toInt()) {
            val count = field(moov, stts, 1 + 2 * i)
            val delta = field(moov, stts, 2 + 2 * i)
            for (k in 0 until count) {
                times.add(t)
                t += delta
                if (times.size > 10_000) break
            }
        }

        // Размеры сэмплов
        val stsz = child(moov, stbl.start, stbl.end, "stsz") ?: return emptyList()
        val fixed = field(moov, stsz, 0)
        val sampleCount = field(moov, stsz, 1).toInt().coerceAtMost(10_000)
        val sizes = LongArray(sampleCount) { if (fixed != 0L) fixed else field(moov, stsz, 2 + it) }

        // Смещения чанков
        val offsets = ArrayList<Long>()
        val stco = child(moov, stbl.start, stbl.end, "stco")
        if (stco != null) {
            for (i in 0 until field(moov, stco, 0).toInt()) offsets.add(field(moov, stco, 1 + i))
        } else {
            val co64 = child(moov, stbl.start, stbl.end, "co64") ?: return emptyList()
            for (i in 0 until field(moov, co64, 0).toInt()) offsets.add(u64(moov, co64.start + 8 + 8 * i))
        }

        // Сколько сэмплов в каждом чанке
        val stsc = child(moov, stbl.start, stbl.end, "stsc") ?: return emptyList()
        val entries = (0 until field(moov, stsc, 0).toInt()).map {
            field(moov, stsc, 1 + 3 * it) to field(moov, stsc, 2 + 3 * it)
        }

        val sampleOffsets = ArrayList<Long>()
        for (ci in offsets.indices) {
            val chunk = (ci + 1).toLong()
            var perChunk = 0L
            for ((first, n) in entries) if (first <= chunk) perChunk = n
            var o = offsets[ci]
            for (k in 0 until perChunk) {
                if (sampleOffsets.size >= sizes.size) break
                sampleOffsets.add(o)
                o += sizes[sampleOffsets.size - 1]
            }
        }

        val out = ArrayList<Mark>()
        for (i in 0 until minOf(times.size, sampleOffsets.size)) {
            val raw = readAt(ch, sampleOffsets[i], sizes[i].toInt().coerceIn(0, 4096))
            if (raw.size < 2) continue
            val len = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val text = raw.copyOfRange(2, minOf(raw.size, 2 + len))
            val title = if (text.size >= 2 &&
                ((text[0] == 0xFE.toByte() && text[1] == 0xFF.toByte()) || (text[0] == 0xFF.toByte() && text[1] == 0xFE.toByte()))
            ) {
                String(text, Charsets.UTF_16)
            } else {
                String(text, Charsets.UTF_8)
            }
            out.add(Mark(times[i] * 1000 / timescale, title.trim()))
        }
        return out
    }

    // ---------------- MP3 (ID3v2 CHAP) ----------------

    private class Frame(val id: String, val start: Int, val end: Int)

    private fun id3(ch: FileChannel): List<Mark> {
        val h = readAt(ch, 0, 10)
        val version = h[3].toInt()
        val size = syncsafe(h, 6)
        val d = readAt(ch, 10, size.coerceAtMost(32 shl 20))
        val out = ArrayList<Mark>()
        for (f in frames(d, 0, d.size, version)) {
            if (f.id != "CHAP") continue
            var q = f.start
            while (q < f.end && d[q] != 0.toByte()) q++
            q += 1
            if (q + 16 > f.end) continue
            val start = u32(d, q)
            q += 16
            var title = ""
            for (sub in frames(d, q, f.end, version)) {
                if (sub.id == "TIT2") title = id3Text(d, sub.start, sub.end)
            }
            out.add(Mark(start, title))
        }
        return out
    }

    private fun frames(d: ByteArray, start: Int, end: Int, version: Int): List<Frame> {
        val out = ArrayList<Frame>()
        var p = start
        while (p + 10 <= end) {
            if (d[p] == 0.toByte()) break
            val id = String(d, p, 4, Charsets.ISO_8859_1)
            val size = if (version >= 4) syncsafe(d, p + 4) else u32(d, p + 4).toInt()
            if (size < 0 || p + 10 + size > end) break
            out.add(Frame(id, p + 10, p + 10 + size))
            p += 10 + size
        }
        return out
    }

    private fun id3Text(d: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        val enc = d[start].toInt()
        val bytes = d.copyOfRange(start + 1, end)
        val s = when (enc) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            1 -> String(bytes, Charsets.UTF_16)
            2 -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return s.substringBefore('\u0000').trim()
    }

    // ---------------- байты ----------------

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return if (buf.position() == len) buf.array() else buf.array().copyOf(buf.position())
    }

    private fun u32(d: ByteArray, i: Int): Long =
        ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
            ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)

    private fun u64(d: ByteArray, i: Int): Long = (u32(d, i) shl 32) or u32(d, i + 4)

    private fun syncsafe(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0x7F) shl 21) or ((d[i + 1].toInt() and 0x7F) shl 14) or
            ((d[i + 2].toInt() and 0x7F) shl 7) or (d[i + 3].toInt() and 0x7F)
}
