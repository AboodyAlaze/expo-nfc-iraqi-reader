package expo.modules.nfciraqireader.nfc

/**
 * تغليف/فك تغليف أوامر APDU بعد نجاح BAC.
 */
class SecureMessaging(
    private val ksEnc: ByteArray,
    private val ksMac: ByteArray,
    private var ssc: Long
) {

    private fun sscBytes(): ByteArray {
        val b = ByteArray(8)
        var v = ssc
        for (i in 7 downTo 0) { b[i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return b
    }

    /** ترميز الطول حسب BER: قصير إذا < 128، وإلا طويل */
    private fun encodeLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
    }

    fun wrap(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?, le: Int?): ByteArray {
        val maskedCla = (cla or 0x0C)
        val header = byteArrayOf(maskedCla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        val paddedHeader = Crypto.pad(header)

        var do87 = ByteArray(0)
        if (data != null && data.isNotEmpty()) {
            val enc = Crypto.tdesEncrypt(ksEnc, Crypto.pad(data))
            val body = byteArrayOf(0x01) + enc
            do87 = byteArrayOf(0x87.toByte()) + encodeLen(body.size) + body
        }

        var do97 = ByteArray(0)
        if (le != null) do97 = byteArrayOf(0x97.toByte(), 0x01, le.toByte())

        ssc++
        val macInput = Crypto.pad(sscBytes() + paddedHeader + do87 + do97)
        val cc = Crypto.retailMac(ksMac, macInput)
        val do8e = byteArrayOf(0x8E.toByte(), 0x08) + cc

        val body = do87 + do97 + do8e
        require(body.size < 256) { "الأمر أطول من المسموح" }
        return header + byteArrayOf(body.size.toByte()) + body + byteArrayOf(0x00)
    }

    fun unwrap(response: ByteArray): ByteArray {
        require(response.size >= 2) { "رد فارغ" }
        val body = response.copyOf(response.size - 2)

        var i = 0
        var do87 = ByteArray(0)
        var do99 = ByteArray(0)
        var do8e = ByteArray(0)
        var encData: ByteArray? = null

        while (i < body.size - 1) {
            val tag = body[i].toInt() and 0xFF

            // قراءة الطول: قصير (< 0x80) أو طويل (0x81 / 0x82 ...)
            var j = i + 1
            val first = body[j].toInt() and 0xFF
            val len: Int
            if (first < 0x80) {
                len = first
                j += 1
            } else {
                val n = first and 0x7F
                if (j + n >= body.size) break
                var l = 0
                for (k in 1..n) l = (l shl 8) or (body[j + k].toInt() and 0xFF)
                len = l
                j += 1 + n
            }
            if (j + len > body.size) break

            val value = body.copyOfRange(j, j + len)
            val full = body.copyOfRange(i, j + len)

            when (tag) {
                0x87 -> { do87 = full; encData = value.copyOfRange(1, value.size) }
                0x99 -> do99 = full
                0x8E -> do8e = value
                else -> {}
            }
            i = j + len
        }

        require(do99.size >= 4) { "ما وصلت حالة الرد (DO99)" }

        ssc++
        val expected = Crypto.retailMac(ksMac, Crypto.pad(sscBytes() + do87 + do99))
        require(expected.contentEquals(do8e)) { "فشل التحقق من الـ MAC — الجلسة غير آمنة" }

        val sw = ((do99[2].toInt() and 0xFF) shl 8) or (do99[3].toInt() and 0xFF)
        require(sw == 0x9000) { "البطاقة ردت بحالة: %04X".format(sw) }

        return if (encData != null) Crypto.unpad(Crypto.tdesDecrypt(ksEnc, encData)) else ByteArray(0)
    }
}