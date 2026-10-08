package com.svgfont.maker.font

data class GlyphPoint(val x: Float, val y: Float, val onCurve: Boolean)

data class Glyph(
    val codepoint: Int,
    val advanceWidth: Int,
    val contours: List<List<GlyphPoint>>
)
