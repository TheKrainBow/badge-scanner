package fr.fortytwo.badgescanner.nfc

/**
 * Kotlin port of EXAMPLES/nfc_mifare2wiegand.py.
 *
 * The script's `mifare_input` is the UID bytes exactly as read from the tag,
 * interpreted as a big-endian number (verified against the CA: UID E01CBEDB
 * -> FC 0xBE=190, card 0x1CE0=7392 -> badge id 19007392).
 *
 * With uid bytes u0 u1 u2 u3, the script's outputs map to:
 *  - Mifare Hex ("NFC HEX")    = hex of u0 u1 u2 u3 (the UID as read)
 *  - Facility Code             = u2
 *  - Internal Card Number      = (u1 << 8) | u0
 *  - Wiegand 26 ("NFC W26")    = "<fc><icc padded to 5 digits>"
 *  - IXOFF code                = "<fc><icc>" (no padding)
 *  - Premium ("NFC PREMIUM")   = u3 u2 u1 u0 read as a big-endian number
 */
data class BadgeCodes(
    /** Full UID bytes exactly as read by the phone, hex encoded. */
    val uidHex: String,
    /** "NFC HEX" of the script: the UID (first 4 bytes) as read, in hex. */
    val mifareHex: String,
    /** The script's input value (UID read big-endian, as decimal). */
    val mifareDecimal: Long,
    val facilityCode: Int,
    val cardNumber: Int,
    /** "NFC W26": facility code followed by the card number padded to 5 digits. */
    val wiegand26: String,
    /** "IXOFF" code: facility code followed by the unpadded card number. */
    val wiegandUnpadded: String,
    /** "NFC PREMIUM": the UID bytes reversed, read as a big-endian number. */
    val premium: Long,
) {
    /**
     * Badge ids worth trying against the CA `/users/{id}` endpoint, most
     * likely format first (matches the codes the readers push on the
     * Wiegand bus, which is what the CA stores as user id).
     */
    val caCandidates: List<String>
        get() = listOf(wiegand26, wiegandUnpadded, premium.toString()).distinct()
}

object Wiegand {

    /** Rebuilds the codes from a stored UID hex string (for history reopen). */
    fun fromUidHex(hex: String): BadgeCodes = fromUid(hex.hexToByteArray())

    fun fromUid(uid: ByteArray): BadgeCodes {
        require(uid.isNotEmpty()) { "Empty tag UID" }
        // 7- and 10-byte UIDs: Wiegand readers only use the first 4 bytes.
        // Shorter ids are left-padded with zeros.
        val u = when {
            uid.size >= 4 -> uid.copyOfRange(0, 4)
            else -> ByteArray(4 - uid.size) + uid
        }
        val b0 = u[0].toInt() and 0xFF
        val b1 = u[1].toInt() and 0xFF
        val b2 = u[2].toInt() and 0xFF
        val b3 = u[3].toInt() and 0xFF

        val mifareDecimal = (b0.toLong() shl 24) or (b1.toLong() shl 16) or (b2.toLong() shl 8) or b3.toLong()
        val premium = (b3.toLong() shl 24) or (b2.toLong() shl 16) or (b1.toLong() shl 8) or b0.toLong()
        val facilityCode = b2
        val cardNumber = (b1 shl 8) or b0

        return BadgeCodes(
            uidHex = uid.toHex(),
            mifareHex = "%08X".format(mifareDecimal),
            mifareDecimal = mifareDecimal,
            facilityCode = facilityCode,
            cardNumber = cardNumber,
            wiegand26 = "%d%05d".format(facilityCode, cardNumber),
            wiegandUnpadded = "$facilityCode$cardNumber",
            premium = premium,
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToByteArray(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "Odd-length hex string" }
    return ByteArray(clean.length / 2) {
        clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
