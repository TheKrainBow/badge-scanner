package fr.fortytwo.badgescanner

import fr.fortytwo.badgescanner.util.piscineLoginFromName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiscineLoginTest {

    @Test
    fun `extracts trailing login from a piscine name`() {
        assertEquals("lgauvrea", piscineLoginFromName("[PISCINE] 249 lgauvrea"))
    }

    @Test
    fun `is case-insensitive on the tag and lowercases the login`() {
        assertEquals("jdupont", piscineLoginFromName("[piscine] 12 JDupont"))
    }

    @Test
    fun `ignores extra whitespace`() {
        assertEquals("mmartin", piscineLoginFromName("  [PISCINE]   3    mmartin  "))
    }

    @Test
    fun `keeps hyphens in the login`() {
        assertEquals("mdi-boni", piscineLoginFromName("[PISCINE] 013 mdi-boni"))
    }

    @Test
    fun `returns null for non-piscine names`() {
        assertNull(piscineLoginFromName("Jean Dupond"))
    }

    @Test
    fun `returns null when the trailing token is not a login`() {
        // Only the tag and a number, no login token
        assertNull(piscineLoginFromName("[PISCINE] 249"))
    }
}
