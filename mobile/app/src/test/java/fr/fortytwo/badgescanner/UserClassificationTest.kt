package fr.fortytwo.badgescanner

import fr.fortytwo.badgescanner.api.Coalition
import fr.fortytwo.badgescanner.scan.ScanViewModel.Companion.pickCoalition
import fr.fortytwo.badgescanner.scan.ScanViewModel.Companion.userTypeFromCursus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserClassificationTest {

    @Test
    fun `cursus 21 is a student even with piscine cursus`() {
        assertEquals("Student", userTypeFromCursus(listOf(9, 21)))
    }

    @Test
    fun `only cursus 9 is a pisciner`() {
        assertEquals("Piscine", userTypeFromCursus(listOf(9)))
    }

    @Test
    fun `no relevant cursus is unclassified`() {
        assertNull(userTypeFromCursus(listOf(3, 4)))
    }

    private fun coalition(name: String, slug: String, score: Int = 0) =
        Coalition(id = 1, name = name, slug = slug, color = "#000000", imageUrl = null, coverUrl = null, score = score)

    @Test
    fun `main coalition wins over piscine coalition`() {
        val picked = pickCoalition(
            listOf(
                coalition("Alliance", "alliance", score = 9000),
                coalition("Harkonnen", "harkonnen", score = 100),
            )
        )
        assertEquals("Harkonnen", picked?.name)
    }

    @Test
    fun `piscine coalition shown when no main coalition`() {
        val picked = pickCoalition(listOf(coalition("Hordes", "hordes")))
        assertEquals("Hordes", picked?.name)
    }

    @Test
    fun `priority order harkonnen before atreides`() {
        val picked = pickCoalition(
            listOf(coalition("Atreides", "atreides"), coalition("Harkonnen", "harkonnen"))
        )
        assertEquals("Harkonnen", picked?.name)
    }

    @Test
    fun `main coalition matched by name even when slug is campus-prefixed`() {
        val picked = pickCoalition(
            listOf(
                coalition("Alliance", "alliance", score = 9000),
                coalition("Corrino", "42nice-corrino", score = 100),
            )
        )
        assertEquals("Corrino", picked?.name)
    }

    @Test
    fun `falls back to highest score when unknown coalitions`() {
        val picked = pickCoalition(
            listOf(coalition("X", "x", score = 5), coalition("Y", "y", score = 50))
        )
        assertEquals("Y", picked?.name)
    }
}
