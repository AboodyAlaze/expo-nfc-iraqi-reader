package expo.modules.nfciraqireader.nfc

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * كل العمليات التشفيرية المطلوبة لبروتوكول BAC
 * (3DES بنمط CBC + Retail MAC حسب ISO 9797-1 Algorithm 3)
 */
object Crypto {

    /** Padding method 2: نضيف 0x80 ثم أصفار لحد ما يصير الطول من مضاعفات 8 */
    fun pad(data: ByteArray): ByteArray {
        val out = data + 0x80.toByte()
        val rem = out.size % 8
        return if (rem == 0) out else out + ByteArray(8 - rem)
    }

    fun unpad(data: ByteArray): ByteArray {
        var i = data.size - 1
        while (i >= 0 && data[i].toInt() == 0) i--
        require(i >= 0 && data[i] == 0x80.toByte()) { "padding غير صحيح" }
        return data.copyOf(i)
    }

    /** مفتاح 16 بايت (2-key 3DES) نحوله لـ 24 بايت K1|K2|K1 */
    private fun expand(k16: ByteArray): ByteArray = k16 + k16.copyOf(8)

    fun tdesEncrypt(key16: ByteArray, data: ByteArray, iv: ByteArray = ByteArray(8)): ByteArray {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(expand(key16), "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun tdesDecrypt(key16: ByteArray, data: ByteArray, iv: ByteArray = ByteArray(8)): ByteArray {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(expand(key16), "DESede"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /**
     * Retail MAC (ISO 9797-1 Alg 3): CBC-DES بالمفتاح الأول على كل البلوكات،
     * بعدين فك تشفير آخر بلوك بالمفتاح الثاني وإعادة تشفيره بالأول.
     * البيانات لازم تجي مبطّنة (padded) مسبقاً.
     */
    fun retailMac(key16: ByteArray, paddedData: ByteArray): ByteArray {
        require(paddedData.size % 8 == 0) { "البيانات لازم تكون مضاعف 8" }
        val ka = SecretKeySpec(key16.copyOf(8), "DES")
        val kb = SecretKeySpec(key16.copyOfRange(8, 16), "DES")

        val cbc = Cipher.getInstance("DES/CBC/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, ka, IvParameterSpec(ByteArray(8)))
        }.doFinal(paddedData)

        val last = cbc.copyOfRange(cbc.size - 8, cbc.size)

        val t = Cipher.getInstance("DES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, kb)
        }.doFinal(last)

        return Cipher.getInstance("DES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, ka)
        }.doFinal(t)
    }

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        java.security.SecureRandom().nextBytes(b)
        return b
    }

    fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
