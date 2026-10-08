package com.svgfont.maker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.svgfont.maker.font.Glyph
import com.svgfont.maker.font.GlyphPoint

class GlyphPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var contours: List<List<GlyphPoint>> = emptyList()
    private var advance = 1000f
    private val unitsPerEm = 1000f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1A1A1A.toInt()
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFFCFCFCF.toInt()
    }

    fun setGlyph(g: Glyph?) {
        contours = g?.contours ?: emptyList()
        advance = (g?.advanceWidth ?: 1000).toFloat().coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = 24f * resources.displayMetrics.density
        val w = width - 2 * pad
        val h = height - 2 * pad
        if (w <= 0 || h <= 0) return

        val s = minOf(w / advance, h / unitsPerEm)
        val ox = pad + (w - advance * s) / 2f
        val baseY = pad + (h + unitsPerEm * s) / 2f

        // guides
        canvas.drawLine(ox, baseY, ox + advance * s, baseY, guidePaint)
        canvas.drawRect(ox, baseY - unitsPerEm * s, ox + advance * s, baseY, guidePaint)

        if (contours.isEmpty()) return

        val path = buildPath(contours)
        canvas.save()
        canvas.translate(ox, baseY)
        canvas.scale(s, -s)
        canvas.drawPath(path, fillPaint)
        canvas.restore()
    }

    private fun buildPath(src: List<List<GlyphPoint>>): Path {
        val path = Path()
        for (c0 in src) {
            if (c0.size < 2) continue
            val pts = ArrayList<GlyphPoint>(c0)

            if (!pts[0].onCurve) {
                val last = pts[pts.size - 1]
                if (!last.onCurve) {
                    pts.add(0, GlyphPoint((last.x + pts[0].x) / 2f, (last.y + pts[0].y) / 2f, true))
                } else {
                    pts.add(0, pts.removeAt(pts.size - 1))
                }
            }

            path.moveTo(pts[0].x, pts[0].y)
            var i = 1
            while (i < pts.size) {
                val p = pts[i]
                if (p.onCurve) {
                    path.lineTo(p.x, p.y)
                    i++
                } else {
                    val n = pts[(i + 1) % pts.size]
                    if (n.onCurve) {
                        path.quadTo(p.x, p.y, n.x, n.y)
                        i += 2
                    } else {
                        path.quadTo(p.x, p.y, (p.x + n.x) / 2f, (p.y + n.y) / 2f)
                        i += 1
                    }
                }
            }
            path.close()
        }
        return path
    }
}
