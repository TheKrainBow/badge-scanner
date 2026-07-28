package fr.fortytwo.badgescanner

import fr.fortytwo.badgescanner.nfc.Wiegand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values generated with the reference script:
 *   python3 EXAMPLES/nfc_mifare2wiegand.py hex <uid_hex>
 * where <uid_hex> is the UID exactly as the phone reads it (big-endian),
 * confirmed against the CA with a real badge (see `heinz badge` test).
 */
class WiegandTest {

    @Test
    fun `heinz badge E01CBEDB gives the badge id stored on the CA`() {
        val codes = Wiegand.fromUid(byteArrayOf(0xE0.toByte(), 0x1C, 0xBE.toByte(), 0xDB.toByte()))
        assertEquals("E01CBEDB", codes.uidHex)
        assertEquals(190, codes.facilityCode)
        assertEquals(7392, codes.cardNumber)
        assertEquals("19007392", codes.wiegand26)
    }

    @Test
    fun `matches script output for F204632A`() {
        val codes = Wiegand.fromUid(byteArrayOf(0xF2.toByte(), 0x04, 0x63, 0x2A))
        assertEquals("F204632A", codes.uidHex)
        assertEquals("F204632A", codes.mifareHex)
        assertEquals(99, codes.facilityCode)
        assertEquals(1266, codes.cardNumber)
        assertEquals("9901266", codes.wiegand26)
        assertEquals("991266", codes.wiegandUnpadded)
        assertEquals(711132402L, codes.premium)
    }

    @Test
    fun `matches script output for 04A2C81D`() {
        val codes = Wiegand.fromUid(byteArrayOf(0x04, 0xA2.toByte(), 0xC8.toByte(), 0x1D))
        assertEquals("04A2C81D", codes.mifareHex)
        assertEquals(200, codes.facilityCode)
        assertEquals(41476, codes.cardNumber)
        assertEquals("20041476", codes.wiegand26)
        assertEquals("20041476", codes.wiegandUnpadded)
        assertEquals(499687940L, codes.premium)
    }

    @Test
    fun `matches script output for ABCD1ACE`() {
        val codes = Wiegand.fromUid(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x1A, 0xCE.toByte())
        )
        assertEquals("ABCD1ACE", codes.mifareHex)
        assertEquals(26, codes.facilityCode)
        assertEquals(52651, codes.cardNumber)
        assertEquals("2652651", codes.wiegand26)
        assertEquals("2652651", codes.wiegandUnpadded)
        assertEquals(3457863083L, codes.premium)
    }

    @Test
    fun `uses first four bytes of longer UIDs`() {
        val short = Wiegand.fromUid(byteArrayOf(0xF2.toByte(), 0x04, 0x63, 0x2A))
        val long = Wiegand.fromUid(byteArrayOf(0xF2.toByte(), 0x04, 0x63, 0x2A, 0x11, 0x22, 0x33))
        assertEquals(short.wiegand26, long.wiegand26)
        assertEquals(short.premium, long.premium)
    }

    @Test
    fun `ca candidates are deduplicated and ordered`() {
        val codes = Wiegand.fromUid(byteArrayOf(0x04, 0xA2.toByte(), 0xC8.toByte(), 0x1D))
        // padded and unpadded are identical here -> only two candidates
        assertEquals(listOf("20041476", "499687940"), codes.caCandidates)
    }
}
