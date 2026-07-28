package fr.fortytwo.badgescanner

import fr.fortytwo.badgescanner.api.CaDirEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaDirEntryTest {

    @Test
    fun `ft_login makes a user listable`() {
        val e = CaDirEntry(pk = 1, fullName = "Jean Dupond", ftLogin = "jdupond")
        assertTrue(e.isListable)
        assertEquals("jdupond", e.displayLogin)
    }

    @Test
    fun `piscine name gives a login and is listable`() {
        val e = CaDirEntry(pk = 2, fullName = "[PISCINE] 013 mdi-boni")
        assertTrue(e.isListable)
        assertEquals("mdi-boni", e.displayLogin)
    }

    @Test
    fun `user without any login is not listable`() {
        val e = CaDirEntry(pk = 3, fullName = "Some Machine")
        assertFalse(e.isListable)
    }

    @Test
    fun `3b3 accounts are excluded by name`() {
        val e = CaDirEntry(pk = 4, fullName = "3b3r2p1", ftLogin = "3b3r2p1")
        assertFalse(e.isListable)
    }

    @Test
    fun `3b3 excluded even if login field differs`() {
        val e = CaDirEntry(pk = 5, fullName = "3b3-something", ftLogin = "realuser")
        assertFalse(e.isListable)
    }
}
