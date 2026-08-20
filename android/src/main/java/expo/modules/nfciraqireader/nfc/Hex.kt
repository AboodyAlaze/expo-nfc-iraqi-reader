package expo.modules.nfciraqireader.nfc

object Hex {
    fun toHex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
    fun fromHex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(":", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }
}
