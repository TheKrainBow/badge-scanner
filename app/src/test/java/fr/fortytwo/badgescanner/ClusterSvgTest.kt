package fr.fortytwo.badgescanner

import fr.fortytwo.badgescanner.api.parseClusterSvg
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real 42 intra cluster SVGs use two different transform styles depending on
 * the campus (both observed live from cdn.intra.42.fr): a transform carried
 * on an ancestor `<g>` (translate+scale), or carried directly on each
 * element (`matrix(a,0,0,d,e,f)`). Both must resolve to the same kind of
 * absolute seat position.
 */
class ClusterSvgTest {

    @Test
    fun `ancestor g transform (translate+scale) is applied to its rects`() {
        val svg = """
            <svg viewBox="0 0 600 800" xmlns="http://www.w3.org/2000/svg">
                <g id="r1 r2 r3 r4" transform="translate(-420 75) scale(1.75)">
                    <g id="r1">
                        <text font-weight="bold" font-size="20" y="45.66848" x="378.113" fill="#cccccc">R01</text>
                        <rect fill="#e5e5e5" stroke="#7f7f7f" x="553.16835" y="20.49957" width="16" height="20" id="c1r1p1"/>
                        <image xlink:href="" x="553.16835" y="20.49957" width="16" height="20" id="c1r1p1"/>
                    </g>
                </g>
            </svg>
        """.trimIndent()

        val layout = parseClusterSvg(svg)

        assertEquals(600f, layout.viewBoxWidth, 0.001f)
        assertEquals(800f, layout.viewBoxHeight, 0.001f)
        assertEquals(1, layout.seats.size)
        val seat = layout.seats.single()
        assertEquals("c1r1p1", seat.host)
        assertEquals(548.0446f, seat.x, 0.01f)
        assertEquals(110.8742f, seat.y, 0.01f)
        assertEquals(28f, seat.width, 0.01f)
        assertEquals(35f, seat.height, 0.01f)

        assertEquals(1, layout.rowLabels.size)
        assertEquals("R01", layout.rowLabels.single().text)
    }

    @Test
    fun `matrix transform directly on the element is applied`() {
        val svg = """
            <svg viewBox="0 0 600 800" xmlns="http://www.w3.org/2000/svg">
                <g class="r1">
                    <g class="r1-top posts">
                        <rect transform="matrix(1.1671, 0, 0, 1.1671, -39.5137, -4.04201)" fill="#e5e5e5" stroke="#7f7f7f"
                            x="416.56942" y="131.988" width="45.1558" height="56.44476" id="c4r1p1" />
                    </g>
                </g>
                <g class="text">
                    <text transform="matrix(2.06058, 0, 0, 2.06058, -251.351, -99.5144)" font-weight="bold"
                        font-size="20" y="164.63968" x="171.42488" fill="#cccccc">R1</text>
                </g>
            </svg>
        """.trimIndent()

        val layout = parseClusterSvg(svg)

        assertEquals(1, layout.seats.size)
        val seat = layout.seats.single()
        assertEquals("c4r1p1", seat.host)
        assertEquals(446.6645f, seat.x, 0.01f)
        assertEquals(150.0012f, seat.y, 0.01f)
        assertEquals(52.7013f, seat.width, 0.01f)
        assertEquals(65.8767f, seat.height, 0.01f)

        assertEquals(1, layout.rowLabels.size)
        assertEquals("R1", layout.rowLabels.single().text)
    }

    @Test
    fun `non-seat rects (no cNrNpN id) are ignored`() {
        val svg = """
            <svg viewBox="0 0 600 800" xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="600" height="800" fill="#ffffff" id="background"/>
            </svg>
        """.trimIndent()

        val layout = parseClusterSvg(svg)

        assertEquals(0, layout.seats.size)
    }
}
