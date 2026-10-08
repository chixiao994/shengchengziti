package com.svgfont.maker.svg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SvgPoint(val x: Float, val y: Float, val onCurve: Boolean)

data class SvgDoc(val viewBox: FloatArray, val contours: List<List<SvgPoint>>)

object SvgParser {

    private val TAG_RE = Regex(
        """<\s*(path|rect|circle|ellipse|polygon|polyline|line)\b([^>]*)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val ATTR_RE = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val VIEWBOX_RE = Regex("""viewBox\s*=\s*["']([^"']*)["']""")
    private val WIDTH_RE = Regex("""\bwidth\s*=\s*["']([\d.]+)""")
    private val HEIGHT_RE = Regex("""\bheight\s*=\s*["']([\d.]+)""")

    fun parse(text: String): SvgDoc {
        val raw = ArrayList<ArrayList<SvgPoint>>()

        for (m in TAG_RE.findAll(text)) {
            val tag = m.groupValues[1].lowercase()
            val attrs = parseAttrs(m.groupValues[2])
            val local = ArrayList<ArrayList<SvgPoint>>()
            when (tag) {
                "path" -> attrs["d"]?.let { parsePathData(it, local) }
                "rect" -> parseRect(attrs, local)
                "circle" -> parseCircle(attrs, local)
                "ellipse" -> parseEllipse(attrs, local)
                "polygon" -> parsePoly(attrs, true, local)
                "polyline" -> parsePoly(attrs, false, local)
            }
            val mat = parseTransform(attrs["transform"])
            if (mat != null) {
                for (c in local) {
                    val nc = ArrayList<SvgPoint>(c.size)
                    for (p in c) {
                        val r = mat.apply(p.x, p.y)
                        nc.add(SvgPoint(r[0], r[1], p.onCurve))
                    }
                    raw.add(nc)
                }
            } else {
                raw.addAll(local)
            }
        }

        val contours = ArrayList<List<SvgPoint>>()
        for (c in raw) {
            val clean = cleanContour(c)
            if (clean.size >= 2) contours.add(clean)
        }

        var vb = findViewBox(text)
        if (vb == null) {
            val w = WIDTH_RE.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            val h = HEIGHT_RE.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            vb = if (w != null && h != null && w > 0f && h > 0f) {
                floatArrayOf(0f, 0f, w, h)
            } else {
                bbox(contours)
            }
        }
        return SvgDoc(vb, contours)
    }

    // ---------------- helpers ----------------

    private fun parseAttrs(s: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in ATTR_RE.findAll(s)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
            map[m.groupValues[1].lowercase()] = v
        }
        return map
    }

    private fun findViewBox(text: String): FloatArray? {
        val m = VIEWBOX_RE.find(text) ?: return null
        val parts = m.groupValues[1].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        return if (parts.size == 4) parts.toFloatArray() else null
    }

    private fun bbox(contours: List<List<SvgPoint>>): FloatArray {
        if (contours.isEmpty()) return floatArrayOf(0f, 0f, 1000f, 1000f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (c in contours) for (p in c) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        return floatArrayOf(minX, minY, w, h)
    }

    private fun cleanContour(input: List<SvgPoint>): ArrayList<SvgPoint> {
        val res = ArrayList<SvgPoint>(input.size)
        for (p in input) {
            if (res.isNotEmpty()) {
                val q = res[res.size - 1]
                if (abs(p.x - q.x) < 1e-4f && abs(p.y - q.y) < 1e-4f && p.onCurve == q.onCurve) continue
            }
            res.add(p)
        }
        while (res.size > 1) {
            val a = res[0]
            val b = res[res.size - 1]
            if (abs(a.x - b.x) < 1e-4f && abs(a.y - b.y) < 1e-4f) res.removeAt(res.size - 1) else break
        }
        return res
    }

    // ---------------- shapes ----------------

    private fun parseRect(attrs: Map<String, String>, out: MutableList<ArrayList<SvgPoint>>) {
        val x = attrs.f("x", 0f)
        val y = attrs.f("y", 0f)
        val w = attrs.f("width", 0f)
        val h = attrs.f("height", 0f)
        if (w <= 0f || h <= 0f) return
        var rx = attrs.f("rx", 0f)
        var ry = attrs.f("ry", 0f)
        if (rx > 0f && ry <= 0f) ry = rx
        if (ry > 0f && rx <= 0f) rx = ry
        rx = rx.coerceAtMost(w / 2f)
        ry = ry.coerceAtMost(h / 2f)

        val c = ArrayList<SvgPoint>()
        if (rx <= 0f || ry <= 0f) {
            c.add(SvgPoint(x, y, true))
            c.add(SvgPoint(x + w, y, true))
            c.add(SvgPoint(x + w, y + h, true))
            c.add(SvgPoint(x, y + h, true))
        } else {
            arcSamples(c, x + rx, y + ry, rx, ry, PI.toFloat(), (PI * 1.5).toFloat(), 8)
            arcSamples(c, x + w - rx, y + ry, rx, ry, (PI * 1.5).toFloat(), (PI * 2).toFloat(), 8)
            arcSamples(c, x + w - rx, y + h - ry, rx, ry, 0f, (PI * 0.5).toFloat(), 8)
            arcSamples(c, x + rx, y + h - ry, rx, ry, (PI * 0.5).toFloat(), PI.toFloat(), 8)
        }
        out.add(c)
    }

    private fun parseCircle(attrs: Map<String, String>, out: MutableList<ArrayList<SvgPoint>>) {
        val cx = attrs.f("cx", 0f)
        val cy = attrs.f("cy", 0f)
        val r = attrs.f("r", 0f)
        if (r <= 0f) return
        val c = ArrayList<SvgPoint>()
        arcSamples(c, cx, cy, r, r, 0f, (PI * 2).toFloat(), 32)
        out.add(c)
    }

    private fun parseEllipse(attrs: Map<String, String>, out: MutableList<ArrayList<SvgPoint>>) {
        val cx = attrs.f("cx", 0f)
        val cy = attrs.f("cy", 0f)
        val rx = attrs.f("rx", 0f)
        val ry = attrs.f("ry", 0f)
        if (rx <= 0f || ry <= 0f) return
        val c = ArrayList<SvgPoint>()
        arcSamples(c, cx, cy, rx, ry, 0f, (PI * 2).toFloat(), 32)
        out.add(c)
    }

    private fun parsePoly(attrs: Map<String, String>, closed: Boolean, out: MutableList<ArrayList<SvgPoint>>) {
        val s = attrs["points"] ?: return
        val nums = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (nums.size < 4) return
        val c = ArrayList<SvgPoint>()
        var i = 0
        while (i + 1 < nums.size) {
            c.add(SvgPoint(nums[i], nums[i + 1], true))
            i += 2
        }
        if (closed) out.add(c)
    }

    private fun arcSamples(
        out: MutableList<SvgPoint>,
        cx: Float, cy: Float, rx: Float, ry: Float,
        a0: Float, a1: Float, steps: Int
    ) {
        for (i in 0..steps) {
            val t = a0 + (a1 - a0) * i / steps
            out.add(SvgPoint(cx + rx * cos(t), cy + ry * sin(t), true))
        }
    }

    // ---------------- path data ----------------

    private class Scanner(val s: String) {
        var i = 0
        fun skip() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }
        fun hasNext(): Boolean { skip(); return i < s.length }
        fun peekCmd(): Char? {
            skip()
            if (i >= s.length) return null
            val c = s[i]
            return if (c.isLetter()) c else null
        }
        fun nextCmd(): Char { skip(); return s[i++] }
        fun nextNum(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                var d = 0
                while (i < s.length && s[i].isDigit()) { i++; d++ }
                if (d == 0) i = save
            }
            if (i == start) { i++; return 0f }
            return s.substring(start, i).toFloatOrNull() ?: 0f
        }
    }

    private fun parsePathData(d: String, out: MutableList<ArrayList<SvgPoint>>) {
        val sc = Scanner(d)
        var cx = 0f; var cy = 0f
        var sx = 0f; var sy = 0f
        var lastCmd = ' '
        var lastCtrlX = 0f; var lastCtrlY = 0f
        var lastIsCubic = false
        var lastIsQuad = false
        var cur: ArrayList<SvgPoint>? = null

        fun ensureCur() {
            if (cur == null) {
                cur = ArrayList()
                out.add(cur!!)
                cur!!.add(SvgPoint(cx, cy, true))
            }
        }

        while (sc.hasNext()) {
            val c: Char = sc.peekCmd() ?: when (lastCmd) {
                'M' -> 'L'
                'm' -> 'l'
                'Z', 'z', ' ' -> break
                else -> lastCmd
            }.also { }
            val cmd = if (sc.peekCmd() != null) sc.nextCmd() else c
            lastCmd = cmd

            when (cmd) {
                'M' -> {
                    val x = sc.nextNum(); val y = sc.nextNum()
                    cx = x; cy = y; sx = x; sy = y
                    cur = ArrayList()
                    out.add(cur!!)
                    cur!!.add(SvgPoint(x, y, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'm' -> {
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    cx = x; cy = y; sx = x; sy = y
                    cur = ArrayList()
                    out.add(cur!!)
                    cur!!.add(SvgPoint(x, y, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'L' -> {
                    val x = sc.nextNum(); val y = sc.nextNum()
                    cx = x; cy = y
                    ensureCur(); cur!!.add(SvgPoint(x, y, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'l' -> {
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    cx = x; cy = y
                    ensureCur(); cur!!.add(SvgPoint(x, y, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'H' -> {
                    val x = sc.nextNum(); cx = x
                    ensureCur(); cur!!.add(SvgPoint(x, cy, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'h' -> {
                    cx += sc.nextNum()
                    ensureCur(); cur!!.add(SvgPoint(cx, cy, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'V' -> {
                    val y = sc.nextNum(); cy = y
                    ensureCur(); cur!!.add(SvgPoint(cx, y, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'v' -> {
                    cy += sc.nextNum()
                    ensureCur(); cur!!.add(SvgPoint(cx, cy, true))
                    lastIsCubic = false; lastIsQuad = false
                }
                'C' -> {
                    val x1 = sc.nextNum(); val y1 = sc.nextNum()
                    val x2 = sc.nextNum(); val y2 = sc.nextNum()
                    val x = sc.nextNum(); val y = sc.nextNum()
                    ensureCur()
                    cubicToQuad(cx, cy, x1, y1, x2, y2, x, y, cur!!)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastIsCubic = true; lastIsQuad = false
                }
                'c' -> {
                    val x1 = cx + sc.nextNum(); val y1 = cy + sc.nextNum()
                    val x2 = cx + sc.nextNum(); val y2 = cy + sc.nextNum()
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    ensureCur()
                    cubicToQuad(cx, cy, x1, y1, x2, y2, x, y, cur!!)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastIsCubic = true; lastIsQuad = false
                }
                'S' -> {
                    val rx = if (lastIsCubic) 2 * cx - lastCtrlX else cx
                    val ry = if (lastIsCubic) 2 * cy - lastCtrlY else cy
                    val x2 = sc.nextNum(); val y2 = sc.nextNum()
                    val x = sc.nextNum(); val y = sc.nextNum()
                    ensureCur()
                    cubicToQuad(cx, cy, rx, ry, x2, y2, x, y, cur!!)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastIsCubic = true; lastIsQuad = false
                }
                's' -> {
                    val rx = if (lastIsCubic) 2 * cx - lastCtrlX else cx
                    val ry = if (lastIsCubic) 2 * cy - lastCtrlY else cy
                    val x2 = cx + sc.nextNum(); val y2 = cy + sc.nextNum()
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    ensureCur()
                    cubicToQuad(cx, cy, rx, ry, x2, y2, x, y, cur!!)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2
                    lastIsCubic = true; lastIsQuad = false
                }
                'Q' -> {
                    val x1 = sc.nextNum(); val y1 = sc.nextNum()
                    val x = sc.nextNum(); val y = sc.nextNum()
                    ensureCur()
                    cur!!.add(SvgPoint(x1, y1, false))
                    cur!!.add(SvgPoint(x, y, true))
                    cx = x; cy = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastIsQuad = true; lastIsCubic = false
                }
                'q' -> {
                    val x1 = cx + sc.nextNum(); val y1 = cy + sc.nextNum()
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    ensureCur()
                    cur!!.add(SvgPoint(x1, y1, false))
                    cur!!.add(SvgPoint(x, y, true))
                    cx = x; cy = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastIsQuad = true; lastIsCubic = false
                }
                'T' -> {
                    val x1 = if (lastIsQuad) 2 * cx - lastCtrlX else cx
                    val y1 = if (lastIsQuad) 2 * cy - lastCtrlY else cy
                    val x = sc.nextNum(); val y = sc.nextNum()
                    ensureCur()
                    cur!!.add(SvgPoint(x1, y1, false))
                    cur!!.add(SvgPoint(x, y, true))
                    cx = x; cy = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastIsQuad = true; lastIsCubic = false
                }
                't' -> {
                    val x1 = if (lastIsQuad) 2 * cx - lastCtrlX else cx
                    val y1 = if (lastIsQuad) 2 * cy - lastCtrlY else cy
                    val x = cx + sc.nextNum(); val y = cy + sc.nextNum()
                    ensureCur()
                    cur!!.add(SvgPoint(x1, y1, false))
                    cur!!.add(SvgPoint(x, y, true))
                    cx = x; cy = y
                    lastCtrlX = x1; lastCtrlY = y1
                    lastIsQuad = true; lastIsCubic = false
                }
                'A', 'a' -> {
                    val rel = cmd == 'a'
                    val rx = sc.nextNum(); val ry = sc.nextNum()
                    val rot = sc.nextNum()
                    val large = sc.nextNum() != 0f
                    val sweep = sc.nextNum() != 0f
                    var x = sc.nextNum(); var y = sc.nextNum()
                    if (rel) { x += cx; y += cy }
                    ensureCur()
                    arcToCurve(cx, cy, rx, ry, rot, large, sweep, x, y, cur!!)
                    cx = x; cy = y
                    lastIsCubic = false; lastIsQuad = false
                }
                'Z', 'z' -> {
                    cx = sx; cy = sy
                    cur = null
                    lastIsCubic = false; lastIsQuad = false
                }
            }
        }
    }

    private fun cubicToQuad(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        cur: MutableList<SvgPoint>
    ) {
        val mx = (x0 + 3 * x1 + 3 * x2 + x3) / 8f
        val my = (y0 + 3 * y1 + 3 * y2 + y3) / 8f
        quadApprox(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f, (x0 + 2 * x1 + x2) / 4f, (y0 + 2 * y1 + y2) / 4f, mx, my, cur)
        quadApprox(mx, my, (x1 + 2 * x2 + x3) / 4f, (y1 + 2 * y2 + y3) / 4f, (x2 + x3) / 2f, (y2 + y3) / 2f, x3, y3, cur)
    }

    private fun quadApprox(
        x0: Float, y0: Float, x1: Float, y1: Float,
        x2: Float, y2: Float, x3: Float, y3: Float,
        cur: MutableList<SvgPoint>
    ) {
        val qx = (3 * x1 + 3 * x2 - x0 - x3) / 4f
        val qy = (3 * y1 + 3 * y2 - y0 - y3) / 4f
        cur.add(SvgPoint(qx, qy, false))
        cur.add(SvgPoint(x3, y3, true))
    }

    private fun arcToCurve(
        x1: Float, y1: Float, rx0: Float, ry0: Float, phiDeg: Float,
        largeArc: Boolean, sweep: Boolean, x2: Float, y2: Float,
        out: MutableList<SvgPoint>
    ) {
        var rx = abs(rx0)
        var ry = abs(ry0)
        if (rx < 1e-6f || ry < 1e-6f || (abs(x1 - x2) < 1e-6f && abs(y1 - y2) < 1e-6f)) {
            out.add(SvgPoint(x2, y2, true))
            return
        }
        val phi = Math.toRadians(phiDeg.toDouble())
        val cosP = cos(phi)
        val sinP = sin(phi)
        val dx = ((x1 - x2) / 2.0)
        val dy = ((y1 - y2) / 2.0)
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy

        var rx2 = rx.toDouble() * rx
        var ry2 = ry.toDouble() * ry
        val lambda = (x1p * x1p) / rx2 + (y1p * y1p) / ry2
        if (lambda > 1.0) {
            val s = sqrt(lambda)
            rx = (rx * s).toFloat()
            ry = (ry * s).toFloat()
            rx2 = rx.toDouble() * rx
            ry2 = ry.toDouble() * ry
        }

        val sign = if (largeArc != sweep) 1.0 else -1.0
        var num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
        if (num < 0) num = 0.0
        val den = rx2 * y1p * y1p + ry2 * x1p * x1p
        val coef = if (den == 0.0) 0.0 else sign * sqrt(num / den)
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val theta1 = angleBetween(1.0, 0.0, ux, uy)
        var dTheta = angleBetween(ux, uy, vx, vy)
        if (!sweep && dTheta > 0) dTheta -= 2 * PI
        if (sweep && dTheta < 0) dTheta += 2 * PI

        val n = maxOf(4, ceil(abs(dTheta) / (PI / 16)).toInt())
        for (i in 1 until n) {
            val t = theta1 + dTheta * i / n
            val ct = cos(t)
            val st = sin(t)
            val px = cx + rx * ct * cosP - ry * st * sinP
            val py = cy + rx * ct * sinP + ry * st * cosP
            out.add(SvgPoint(px.toFloat(), py.toFloat(), true))
        }
        out.add(SvgPoint(x2, y2, true))
    }

    private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        var a = if (len == 0.0) 0.0 else acos((dot / len).coerceIn(-1.0, 1.0))
        if (ux * vy - uy * vx < 0) a = -a
        return a
    }

    // ---------------- transform ----------------

    private class Mat(
        val a: Float, val b: Float, val c: Float,
        val d: Float, val e: Float, val f: Float
    ) {
        fun apply(x: Float, y: Float): FloatArray =
            floatArrayOf(a * x + c * y + e, b * x + d * y + f)

        fun mul(o: Mat): Mat = Mat(
            a * o.a + c * o.b,
            b * o.a + d * o.b,
            a * o.c + c * o.d,
            b * o.c + d * o.d,
            a * o.e + c * o.f + e,
            b * o.e + d * o.f + f
        )
    }

    private val TRANSFORM_RE = Regex("""(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)""")

    private fun parseTransform(s: String?): Mat? {
        if (s.isNullOrBlank()) return null
        var m = Mat(1f, 0f, 0f, 1f, 0f, 0f)
        var found = false
        for (match in TRANSFORM_RE.findAll(s)) {
            found = true
            val name = match.groupValues[1]
            val nums = match.groupValues[2].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            val t: Mat = when (name) {
                "matrix" -> if (nums.size >= 6) Mat(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
                else Mat(1f, 0f, 0f, 1f, 0f, 0f)
                "translate" -> Mat(1f, 0f, 0f, 1f, nums.getOrElse(0) { 0f }, nums.getOrElse(1) { 0f })
                "scale" -> {
                    val sx = nums.getOrElse(0) { 1f }
                    val sy = nums.getOrElse(1) { sx }
                    Mat(sx, 0f, 0f, sy, 0f, 0f)
                }
                "rotate" -> {
                    val ang = Math.toRadians(nums.getOrElse(0) { 0f }.toDouble())
                    val ca = cos(ang).toFloat()
                    val sa = sin(ang).toFloat()
                    var r = Mat(ca, sa, -sa, ca, 0f, 0f)
                    if (nums.size >= 3) {
                        val cx = nums[1]; val cy = nums[2]
                        r = Mat(1f, 0f, 0f, 1f, cx, cy).mul(r).mul(Mat(1f, 0f, 0f, 1f, -cx, -cy))
                    }
                    r
                }
                "skewX" -> {
                    val a = Math.toRadians(nums.getOrElse(0) { 0f }.toDouble())
                    Mat(1f, 0f, kotlin.math.tan(a).toFloat(), 1f, 0f, 0f)
                }
                "skewY" -> {
                    val a = Math.toRadians(nums.getOrElse(0) { 0f }.toDouble())
                    Mat(1f, kotlin.math.tan(a).toFloat(), 0f, 1f, 0f, 0f)
                }
                else -> Mat(1f, 0f, 0f, 1f, 0f, 0f)
            }
            m = m.mul(t)
        }
        return if (found) m else null
    }

    private fun Map<String, String>.f(key: String, def: Float): Float =
        this[key]?.toFloatOrNull() ?: def
}
