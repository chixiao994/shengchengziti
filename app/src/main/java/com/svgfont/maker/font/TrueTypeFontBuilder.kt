package com.svgfont.maker.font

import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.roundToInt

class TrueTypeFontBuilder(
    private val familyName: String = "SvgFont"
) {
    private val unitsPerEm = 1000
    private val ascender = 800
    private val descender = -200
    private val lineGap = 0

    fun build(inputGlyphs: List<Glyph>): ByteArray {
        val glyphs = ArrayList<Glyph>()
        glyphs.add(Glyph(0, 500, emptyList()))

        val used = HashSet<Int>()
        for (g in inputGlyphs) {
            if (g.contours.isEmpty()) continue
            if (g.codepoint <= 0 || g.codepoint > 0xFFFE) continue
            if (!used.add(g.codepoint)) continue
            glyphs.add(g)
        }
        val numGlyphs = glyphs.size

        // ---------- glyf / loca ----------
        val glyfOut = ByteArrayOutputStream()
        val loca = IntArray(numGlyphs + 1)
        val bounds = arrayOfNulls<IntArray>(numGlyphs)
        var maxPoints = 0
        var maxContours = 0

        for (i in 0 until numGlyphs) {
            loca[i] = glyfOut.size()
            val g = glyphs[i]
            maxPoints = maxOf(maxPoints, g.contours.sumOf { it.size })
            maxContours = maxOf(maxContours, g.contours.size)
            val enc = encodeGlyph(g)
            if (enc != null) {
                bounds[i] = enc.second
                glyfOut.write(enc.first)
            }
            while (glyfOut.size() % 4 != 0) glyfOut.write(0)
        }
        loca[numGlyphs] = glyfOut.size()

        // ---------- global bbox ----------
        var gMinX = 0; var gMinY = descender; var gMaxX = unitsPerEm; var gMaxY = ascender
        var first = true
        for (b in bounds) {
            if (b == null) continue
            if (first) {
                gMinX = b[0]; gMinY = b[1]; gMaxX = b[2]; gMaxY = b[3]
                first = false
            } else {
                gMinX = minOf(gMinX, b[0]); gMinY = minOf(gMinY, b[1])
                gMaxX = maxOf(gMaxX, b[2]); gMaxY = maxOf(gMaxY, b[3])
            }
        }

        // ---------- hmtx ----------
        val hmtxOut = ByteArrayOutputStream()
        var advMax = 0
        for (i in 0 until numGlyphs) {
            val g = glyphs[i]
            val lsb = bounds[i]?.get(0) ?: 0
            val aw = g.advanceWidth.coerceIn(0, 65535)
            advMax = maxOf(advMax, aw)
            hmtxOut.u16(aw)
            hmtxOut.i16(lsb)
        }

        // ---------- loca ----------
        val locaOut = ByteArrayOutputStream()
        for (v in loca) locaOut.u32(v)

        // ---------- cmap ----------
        val cmapMap = LinkedHashMap<Int, Int>()
        for (i in 1 until numGlyphs) cmapMap[glyphs[i].codepoint] = i
        val cmapTable = buildCmap(cmapMap)

        // ---------- head ----------
        val headTable = buildHead(gMinX, gMinY, gMaxX, gMaxY, 1)

        // ---------- hhea ----------
        val hheaTable = buildHhea(advMax, numGlyphs)

        // ---------- maxp ----------
        val maxpTable = buildMaxp(numGlyphs, maxPoints, maxContours)

        // ---------- name ----------
        val nameTable = buildName()

        // ---------- post ----------
        val postTable = buildPost()

        // ---------- OS/2 ----------
        val os2Table = buildOS2(cmapMap, advMax)

        val tables = listOf(
            Table("OS/2", os2Table),
            Table("cmap", cmapTable),
            Table("glyf", glyfOut.toByteArray()),
            Table("head", headTable),
            Table("hhea", hheaTable),
            Table("hmtx", hmtxOut.toByteArray()),
            Table("loca", locaOut.toByteArray()),
            Table("maxp", maxpTable),
            Table("name", nameTable),
            Table("post", postTable)
        )

        return assemble(tables)
    }

    // =============== glyph encoding ===============

    private fun encodeGlyph(g: Glyph): Pair<ByteArray, IntArray>? {
        val contours = g.contours.filter { it.size >= 2 }
        if (contours.isEmpty()) return null

        val pts = ArrayList<GlyphPoint>()
        val ends = ArrayList<Int>()
        for (c in contours) {
            pts.addAll(c)
            ends.add(pts.size - 1)
        }
        val n = pts.size

        val xs = IntArray(n)
        val ys = IntArray(n)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (i in 0 until n) {
            val x = pts[i].x.roundToInt()
            val y = pts[i].y.roundToInt()
            xs[i] = x; ys[i] = y
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (minX < -32000 || maxX > 32000 || minY < -32000 || maxY > 32000) return null

        val out = ByteArrayOutputStream()
        out.i16(contours.size)
        out.i16(minX); out.i16(minY); out.i16(maxX); out.i16(maxY)
        for (e in ends) out.u16(e)
        out.u16(0)

        val flags = IntArray(n)
        val xb = ByteArrayOutputStream()
        val yb = ByteArrayOutputStream()
        var px = 0
        var py = 0
        for (i in 0 until n) {
            var f = if (pts[i].onCurve) 1 else 0
            val dx = xs[i] - px
            val dy = ys[i] - py
            px = xs[i]; py = ys[i]

            if (dx == 0) {
                f = f or 0x10
            } else if (dx >= -255 && dx <= 255) {
                f = f or 0x02
                if (dx > 0) { f = f or 0x10; xb.write(dx) } else { xb.write(-dx) }
            } else {
                xb.write((dx shr 8) and 0xFF)
                xb.write(dx and 0xFF)
            }

            if (dy == 0) {
                f = f or 0x20
            } else if (dy >= -255 && dy <= 255) {
                f = f or 0x04
                if (dy > 0) { f = f or 0x20; yb.write(dy) } else { yb.write(-dy) }
            } else {
                yb.write((dy shr 8) and 0xFF)
                yb.write(dy and 0xFF)
            }
            flags[i] = f
        }

        var i = 0
        while (i < n) {
            val f = flags[i]
            var count = 1
            while (i + count < n && flags[i + count] == f && count < 256) count++
            if (count > 1) {
                out.write(f or 0x08)
                out.write(count - 1)
            } else {
                out.write(f)
            }
            i += count
        }
        out.write(xb.toByteArray())
        out.write(yb.toByteArray())

        return Pair(out.toByteArray(), intArrayOf(minX, minY, maxX, maxY))
    }

    // =============== cmap ===============

    private fun buildCmap(map: Map<Int, Int>): ByteArray {
        val codes = map.keys.toIntArray()
        codes.sort()
        val segs = ArrayList<IntArray>()
        var i = 0
        while (i < codes.size) {
            val start = codes[i]
            var end = start
            val g0 = map[start]!!
            var j = i + 1
            while (j < codes.size) {
                val c = codes[j]
                if (c == end + 1 && map[c] == g0 + (c - start)) { end = c; j++ } else break
            }
            segs.add(intArrayOf(start, end, (g0 - start) and 0xFFFF))
            i = j
        }
        segs.add(intArrayOf(0xFFFF, 0xFFFF, 1))

        val segCount = segs.size
        val segCountX2 = segCount * 2
        val e = floor(log(segCount.toDouble(), 2.0)).toInt()
        val searchRange = 2 * (1 shl e)
        val entrySelector = e
        val rangeShift = segCountX2 - searchRange
        val length = 16 + 8 * segCount

        val sub = ByteArrayOutputStream()
        sub.u16(4)
        sub.u16(length)
        sub.u16(0)
        sub.u16(segCountX2)
        sub.u16(searchRange)
        sub.u16(entrySelector)
        sub.u16(rangeShift)
        for (s in segs) sub.u16(s[1])
        sub.u16(0)
        for (s in segs) sub.u16(s[0])
        for (s in segs) sub.i16(s[2])
        for (s in segs) sub.u16(0)

        val out = ByteArrayOutputStream()
        out.u16(0)
        out.u16(1)
        out.u16(3)
        out.u16(1)
        out.u32(12)
        out.write(sub.toByteArray())
        return out.toByteArray()
    }

    // =============== head ===============

    private fun buildHead(xMin: Int, yMin: Int, xMax: Int, yMax: Int, locFormat: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(0x00010000)
        out.u32(0x00010000)
        out.u32(0)
        out.u32(0x5F0F3CF5.toInt())
        out.u16(0x000B)
        out.u16(unitsPerEm)
        val now = System.currentTimeMillis() / 1000L + 2082844800L
        out.u32((now ushr 32).toInt())
        out.u32((now and 0xFFFFFFFFL).toInt())
        out.u32((now ushr 32).toInt())
        out.u32((now and 0xFFFFFFFFL).toInt())
        out.i16(xMin); out.i16(yMin); out.i16(xMax); out.i16(yMax)
        out.u16(0)
        out.u16(8)
        out.u16(2)
        out.u16(locFormat)
        out.u16(0)
        return out.toByteArray()
    }

    // =============== hhea ===============

    private fun buildHhea(advMax: Int, numGlyphs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(0x00010000)
        out.i16(ascender)
        out.i16(descender)
        out.i16(lineGap)
        out.u16(advMax)
        out.i16(0)
        out.i16(0)
        out.i16(advMax)
        out.i16(1)
        out.i16(0)
        out.i16(0)
        out.i16(0); out.i16(0); out.i16(0); out.i16(0)
        out.i16(0)
        out.u16(numGlyphs)
        return out.toByteArray()
    }

    // =============== maxp ===============

    private fun buildMaxp(numGlyphs: Int, maxPoints: Int, maxContours: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(0x00010000)
        out.u16(numGlyphs)
        out.u16(maxPoints)
        out.u16(maxContours)
        out.u16(0)
        out.u16(0)
        out.u16(2)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        return out.toByteArray()
    }

    // =============== name ===============

    private fun buildName(): ByteArray {
        val names = listOf(
            1 to familyName,
            2 to "Regular",
            3 to "$familyName Regular 1.0",
            4 to familyName,
            5 to "Version 1.0",
            6 to "$familyName-Regular"
        )
        val encoded = names.map { it.second.toByteArray(Charsets.UTF_16BE) }
        val count = names.size
        val headerSize = 6 + 12 * count

        val out = ByteArrayOutputStream()
        out.u16(0)
        out.u16(count)
        out.u16(headerSize)

        var strOff = 0
        for (i in 0 until count) {
            out.u16(3)
            out.u16(1)
            out.u16(0x0409)
            out.u16(names[i].first)
            out.u16(encoded[i].size)
            out.u16(strOff)
            strOff += encoded[i].size
        }
        for (b in encoded) out.write(b)
        return out.toByteArray()
    }

    // =============== post ===============

    private fun buildPost(): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(0x00030000)
        out.u32(0)
        out.i16(-75)
        out.i16(50)
        out.u32(0)
        out.u32(0)
        out.u32(0)
        out.u32(0)
        out.u32(0)
        return out.toByteArray()
    }

    // =============== OS/2 ===============

    private fun buildOS2(map: Map<Int, Int>, advMax: Int): ByteArray {
        val firstChar = if (map.isEmpty()) 0xFFFF else map.keys.min()
        val lastChar = if (map.isEmpty()) 0xFFFF else map.keys.max()
        val out = ByteArrayOutputStream()
        out.u16(4)
        out.i16(advMax.coerceAtMost(32767))
        out.u16(400)
        out.u16(5)
        out.u16(0)
        out.i16(650); out.i16(600); out.i16(0); out.i16(75)
        out.i16(650); out.i16(600); out.i16(0); out.i16(350)
        out.i16(50); out.i16(250)
        out.i16(0)
        repeat(10) { out.u8(0) }
        out.u32(0); out.u32(0); out.u32(0); out.u32(0)
        out.tag("SVGF")
        out.u16(0x0040)
        out.u16(firstChar.coerceIn(0, 0xFFFF))
        out.u16(lastChar.coerceIn(0, 0xFFFF))
        out.i16(ascender)
        out.i16(descender)
        out.i16(lineGap)
        out.u16(ascender)
        out.u16(-descender)
        out.u32(0)
        out.u32(0)
        out.i16(500)
        out.i16(700)
        out.u16(0)
        out.u16(32)
        out.u16(1)
        return out.toByteArray()
    }

    // =============== assembly ===============

    private data class Table(val tag: String, val data: ByteArray)

    private fun assemble(tables: List<Table>): ByteArray {
        val sorted = tables.sortedBy { it.tag }
        val n = sorted.size
        val headerSize = 12 + 16 * n
        val offsets = IntArray(n)
        var off = headerSize
        for (i in 0 until n) {
            offsets[i] = off
            off += (sorted[i].data.size + 3) and 3.inv()
        }

        val out = ByteArrayOutputStream()
        out.u32(0x00010000)
        out.u16(n)
        val e = floor(log(n.toDouble(), 2.0)).toInt()
        val searchRange = 16 * (1 shl e)
        out.u16(searchRange)
        out.u16(e)
        out.u16(16 * n - searchRange)

        for (i in 0 until n) {
            out.tag(sorted[i].tag)
            out.u32(checksum(sorted[i].data))
            out.u32(offsets[i])
            out.u32(sorted[i].data.size)
        }
        for (i in 0 until n) {
            out.write(sorted[i].data)
            val padded = (sorted[i].data.size + 3) and 3.inv()
            repeat(padded - sorted[i].data.size) { out.write(0) }
        }

        val bytes = out.toByteArray()
        val headIdx = sorted.indexOfFirst { it.tag == "head" }
        if (headIdx >= 0) {
            val headOffset = offsets[headIdx]
            var sum = 0L
            var i = 0
            while (i < bytes.size) {
                var v = 0L
                for (j in 0..3) {
                    v = (v shl 8) or (if (i + j < bytes.size) (bytes[i + j].toLong() and 0xFF) else 0L)
                }
                sum = (sum + v) and 0xFFFFFFFFL
                i += 4
            }
            val adj = ((0xB1B0AFBAL - sum) and 0xFFFFFFFFL).toInt()
            bytes[headOffset + 8] = (adj ushr 24).toByte()
            bytes[headOffset + 9] = (adj ushr 16).toByte()
            bytes[headOffset + 10] = (adj ushr 8).toByte()
            bytes[headOffset + 11] = adj.toByte()
        }
        return bytes
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size) {
            var v = 0L
            for (j in 0..3) {
                v = (v shl 8) or (if (i + j < data.size) (data[i + j].toLong() and 0xFF) else 0L)
            }
            sum = (sum + v) and 0xFFFFFFFFL
            i += 4
        }
        return sum.toInt()
    }
}

// =============== byte helpers ===============

private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)
private fun ByteArrayOutputStream.u16(v: Int) {
    write((v ushr 8) and 0xFF)
    write(v and 0xFF)
}
private fun ByteArrayOutputStream.i16(v: Int) = u16(v and 0xFFFF)
private fun ByteArrayOutputStream.u32(v: Int) {
    write((v ushr 24) and 0xFF)
    write((v ushr 16) and 0xFF)
    write((v ushr 8) and 0xFF)
    write(v and 0xFF)
}
private fun ByteArrayOutputStream.tag(s: String) {
    for (c in s) write(c.code and 0xFF)
}
