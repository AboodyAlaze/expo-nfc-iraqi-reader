package expo.modules.nfciraqireader.nfc

import java.security.MessageDigest

/**
 * اشتقاق مفاتيح BAC من بيانات الـ MRZ المطبوعة على وجه/ظهر البطاقة.
 * هاي نقطة مهمة: بدون البيانات المطبوعة ما تكدر تفتح الشريحة — هذا مقصود بالتصميم.
 */
object MrzKey {

    private val WEIGHTS = intArrayOf(7, 3, 1)

    fun checkDigit(input: String): Char {
        var sum = 0
        input.forEachIndexed { i, c ->
            val v = when {
                c in '0'..'9' -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                c == '<' -> 0
                else -> throw IllegalArgumentException("حرف غير مسموح: $c")
            }
            sum += v * WEIGHTS[i % 3]
        }
        return '0' + (sum % 10)
    }

    /** docNumber = رقم الوثيقة، dob و expiry بصيغة YYMMDD */
    fun mrzInformation(docNumber: String, dob: String, expiry: String): String {
        val doc = docNumber.uppercase().padEnd(9, '<')
        require(doc.length == 9) { "رقم الوثيقة أطول من 9 خانات — راجع ملاحظات README" }
        require(dob.length == 6 && expiry.length == 6) { "التاريخ لازم يكون YYMMDD" }
        return doc + checkDigit(doc) + dob + checkDigit(dob) + expiry + checkDigit(expiry)
    }

    /** يرجع (Kenc, Kmac) */
    fun deriveBacKeys(docNumber: String, dob: String, expiry: String): Pair<ByteArray, ByteArray> {
        val info = mrzInformation(docNumber, dob, expiry)
        val seed = MessageDigest.getInstance("SHA-1")
            .digest(info.toByteArray(Charsets.US_ASCII))
            .copyOf(16)
        return Pair(kdf(seed, 1), kdf(seed, 2))
    }

    /** counter = 1 للتشفير، 2 للـ MAC */
    fun kdf(seed: ByteArray, counter: Int): ByteArray {
        val d = seed + byteArrayOf(0, 0, 0, counter.toByte())
        val key = MessageDigest.getInstance("SHA-1").digest(d).copyOf(16)
        adjustParity(key)
        return key
    }

    private fun adjustParity(key: ByteArray) {
        for (i in key.indices) {
            val b = key[i].toInt() and 0xFF
            key[i] = if (Integer.bitCount(b and 0xFE) % 2 == 0) (b or 0x01).toByte()
                     else (b and 0xFE).toByte()
        }
    }
}
