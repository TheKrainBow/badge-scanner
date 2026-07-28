package fr.fortytwo.badgescanner.api

/** One seat's absolute position/size in its cluster SVG's viewBox coordinate space. */
data class ClusterSeat(
    /** e.g. "c1r2p3" — matches [ClusterLocation.host]. */
    val host: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** A row label (e.g. "R01"), positioned the same way as a seat. */
data class ClusterRowLabel(val text: String, val x: Float, val y: Float)

data class ClusterLayout(
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val seats: List<ClusterSeat>,
    val rowLabels: List<ClusterRowLabel>,
)

/**
 * Parses a 42 intra cluster SVG (fetched from its `cdn_link`) into seat
 * positions, without a full SVG rendering engine.
 *
 * Every cluster map seen from the API is a flat sequence of `<g>`, `<rect>`
 * and `<text>` tags. A seat's actual on-screen position comes from whichever
 * transform currently applies to it, in one of two forms depending on the
 * campus:
 *  - inherited from an ancestor `<g transform="translate(tx ty) scale(s)">`
 *    (seen on some clusters, e.g. Nice's c1/c2), or
 *  - carried directly on the element itself, as
 *    `transform="matrix(a,0,0,d,e,f)"` (seen on others, e.g. Nice's c3/c4).
 * Both forms are axis-aligned scale+translate only (no rotation/skew, ever,
 * in these maps) — so scanning tags in document order and tracking
 * "whichever transform currently applies" is enough to place every seat
 * correctly in either form, without a real nested-scope SVG parser.
 */
fun parseClusterSvg(svg: String): ClusterLayout {
    val viewBox = Regex("""viewBox="[-0-9.]+\s+[-0-9.]+\s+([0-9.]+)\s+([0-9.]+)"""").find(svg)
    val viewBoxWidth = viewBox?.groupValues?.get(1)?.toFloatOrNull() ?: 600f
    val viewBoxHeight = viewBox?.groupValues?.get(2)?.toFloatOrNull() ?: 800f

    var groupTransform = ClusterTransform.IDENTITY
    val seats = mutableListOf<ClusterSeat>()
    val rowLabels = mutableListOf<ClusterRowLabel>()

    for (m in TAG_REGEX.findAll(svg)) {
        val tag = m.value
        when {
            tag.startsWith("<g") -> {
                attr(tag, "transform")?.let(::parseClusterTransform)?.let { groupTransform = it }
            }
            tag.startsWith("<rect") -> {
                val id = attr(tag, "id") ?: continue
                if (!SEAT_ID_REGEX.matches(id)) continue
                val t = attr(tag, "transform")?.let(::parseClusterTransform) ?: groupTransform
                val x = attr(tag, "x")?.toFloatOrNull() ?: continue
                val y = attr(tag, "y")?.toFloatOrNull() ?: continue
                val w = attr(tag, "width")?.toFloatOrNull() ?: continue
                val h = attr(tag, "height")?.toFloatOrNull() ?: continue
                seats += ClusterSeat(id, t.x(x), t.y(y), t.w(w), t.h(h))
            }
            tag.startsWith("<text") -> {
                if (attr(tag, "font-weight") != "bold") continue
                val content = TEXT_CONTENT_REGEX.find(tag)?.groupValues?.get(1) ?: continue
                if (!ROW_LABEL_REGEX.matches(content)) continue
                val t = attr(tag, "transform")?.let(::parseClusterTransform) ?: groupTransform
                val x = attr(tag, "x")?.toFloatOrNull() ?: continue
                val y = attr(tag, "y")?.toFloatOrNull() ?: continue
                rowLabels += ClusterRowLabel(content, t.x(x), t.y(y))
            }
        }
    }
    return ClusterLayout(viewBoxWidth, viewBoxHeight, seats, rowLabels)
}

private val TAG_REGEX = Regex("""<g\b[^>]*>|<rect\b[^>]*/>|<text\b[^>]*>[^<]*</text>""")
private val TEXT_CONTENT_REGEX = Regex(">([^<]*)<")
private val SEAT_ID_REGEX = Regex("""^c\d+r\d+p\d+$""")
private val ROW_LABEL_REGEX = Regex("""^R\d+$""")

/**
 * `\b` before the name matters: without it, looking up "y" would match the
 * tail of `font-family="..."` (which also ends in "y=") before ever
 * reaching the real `y="..."` attribute — silently breaking any lookup of a
 * single-letter attribute name that another attribute happens to end with.
 */
private fun attr(tag: String, name: String): String? =
    Regex("\\b" + Regex.escape(name) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

/** An axis-aligned scale+translate: `x' = a*x + e`, `y' = d*y + f`. */
private data class ClusterTransform(val a: Float, val d: Float, val e: Float, val f: Float) {
    fun x(px: Float) = a * px + e
    fun y(py: Float) = d * py + f
    fun w(pw: Float) = a * pw
    fun h(ph: Float) = d * ph

    companion object {
        val IDENTITY = ClusterTransform(1f, 1f, 0f, 0f)
    }
}

private val MATRIX_REGEX =
    Regex("""matrix\(\s*([-0-9.]+)[,\s]+([-0-9.]+)[,\s]+([-0-9.]+)[,\s]+([-0-9.]+)[,\s]+([-0-9.]+)[,\s]+([-0-9.]+)\s*\)""")
private val TRANSLATE_SCALE_REGEX =
    Regex("""translate\(\s*([-0-9.]+)[,\s]+([-0-9.]+)\s*\)\s*scale\(\s*([-0-9.]+)\s*\)""")

/** Parses `matrix(a,b,c,d,e,f)` (b/c — rotation/skew — are always 0 here) or `translate(tx ty) scale(s)`. */
private fun parseClusterTransform(raw: String): ClusterTransform? {
    MATRIX_REGEX.find(raw)?.let { m ->
        val v = m.groupValues
        return ClusterTransform(a = v[1].toFloat(), d = v[4].toFloat(), e = v[5].toFloat(), f = v[6].toFloat())
    }
    TRANSLATE_SCALE_REGEX.find(raw)?.let { m ->
        val tx = m.groupValues[1].toFloat()
        val ty = m.groupValues[2].toFloat()
        val s = m.groupValues[3].toFloat()
        return ClusterTransform(a = s, d = s, e = tx, f = ty)
    }
    return null
}
