package com.svgfont.maker.font

import com.svgfont.maker.svg.SvgDoc
import kotlin.math.roundToInt

object GlyphBuilder {

    const val UNITS_PER_EM = 1000

    fun build(doc: SvgDoc, codepoint: Int): Glyph? {
        val vb = doc.viewBox
        if (vb.size < 4) return null
        val vbW = vb[2]
        val vbH = vb[3]
        if (vbH <= 0f) return null

        val scale = UNITS_PER_EM / vbH
        val advance = (vbW * scale).roundToInt().coerceAtLeast(1)

        val contours = ArrayList<List<GlyphPoint>>()
        for (c in doc.contours) {
            val pts = ArrayList<GlyphPoint>(c.size)
            for (p in c) {
                val x = (p.x - vb[0]) * scale
                val y = (vb[1] + vbH - p.y) * scale
                pts.add(GlyphPoint(x, y, p.onCurve))
            }
            if (pts.size >= 2) contours.add(pts)
        }
        if (contours.isEmpty()) return null
        return Glyph(codepoint, advance, contours)
    }
}
