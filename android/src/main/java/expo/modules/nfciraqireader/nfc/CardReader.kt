package expo.modules.nfciraqireader.nfc

import android.nfc.tech.IsoDep

class CardException(message: String) : Exception(message)

class CardReader(private val iso: IsoDep, private val logger: (String) -> Unit = {}) {

    private var sm: SecureMessaging? = null

    companion object {
        val AID_EMRTD = Hex.fromHex("A0 00 00 02 47 10 01")

        const val FID_EF_CARD_ACCESS = 0x011C
        const val FID_EF_COM = 0x011E
        const val FID_EF_SOD = 0x011D
        const val FID_DG1 = 0x0101
        const val FID_DG2 = 0x0102
        const val FID_DG11 = 0x010B
        const val FID_DG12 = 0x010C
        const val FID_DG13 = 0x010D
    }

    private fun transceive(apdu: ByteArray): ByteArray {
        logger("→ ${Hex.toHex(apdu)}")
        val r = iso.transceive(apdu)
        logger("← ${Hex.toHex(r)}")
        return r
    }

    private fun sw(r: ByteArray) =
        ((r[r.size - 2].toInt() and 0xFF) shl 8) or (r[r.size - 1].toInt() and 0xFF)

    private fun checkOk(r: ByteArray): ByteArray {
        val s = sw(r)
        if (s != 0x9000) throw CardException("الشريحة ردت بحالة %04X".format(s))
        return r.copyOf(r.size - 2)
    }

    fun selectApplet(aid: ByteArray = AID_EMRTD): Boolean {
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, aid.size.toByte()) + aid
        return sw(transceive(apdu)) == 0x9000
    }

    fun tryReadCardAccess(): ByteArray? = try {
        selectFilePlain(FID_EF_CARD_ACCESS)
        readBinaryPlain()
    } catch (e: Exception) {
        logger("EF.CardAccess غير متاح: ${e.message}")
        null
    }

    fun performBac(docNumber: String, dob: String, expiry: String) {
        val (kEnc, kMac) = MrzKey.deriveBacKeys(docNumber, dob, expiry)

        val rndIcc = checkOk(transceive(Hex.fromHex("00 84 00 00 08")))
        require(rndIcc.size == 8)

        val rndIfd = Crypto.randomBytes(8)
        val kIfd = Crypto.randomBytes(16)
        val s = rndIfd + rndIcc + kIfd

        val eIfd = Crypto.tdesEncrypt(kEnc, s)
        val mIfd = Crypto.retailMac(kMac, Crypto.pad(eIfd))
        val cmdData = eIfd + mIfd

        val apdu = byteArrayOf(0x00, 0x82.toByte(), 0x00, 0x00, cmdData.size.toByte()) +
                cmdData + byteArrayOf(0x28)
        val resp = try {
            checkOk(transceive(apdu))
        } catch (e: CardException) {
            throw CardException("فشل BAC — تأكد من رقم الوثيقة وتاريخ الميلاد والانتهاء. (${e.message})")
        }

        val eIcc = resp.copyOf(32)
        val mIcc = resp.copyOfRange(32, 40)
        require(Crypto.retailMac(kMac, Crypto.pad(eIcc)).contentEquals(mIcc)) {
            "MAC الرد من البطاقة غير صحيح"
        }
        val decrypted = Crypto.tdesDecrypt(kEnc, eIcc)
        val rndIccBack = decrypted.copyOf(8)
        val rndIfdBack = decrypted.copyOfRange(8, 16)
        val kIcc = decrypted.copyOfRange(16, 32)
        require(rndIfdBack.contentEquals(rndIfd) && rndIccBack.contentEquals(rndIcc)) {
            "الأرقام العشوائية ما تطابقت"
        }

        val seed = Crypto.xor(kIfd, kIcc)
        val ksEnc = MrzKey.kdf(seed, 1)
        val ksMac = MrzKey.kdf(seed, 2)

        var ssc = 0L
        for (i in 4..7) ssc = (ssc shl 8) or (rndIcc[i].toLong() and 0xFF)
        for (i in 4..7) ssc = (ssc shl 8) or (rndIfd[i].toLong() and 0xFF)

        sm = SecureMessaging(ksEnc, ksMac, ssc)
        logger("✔ BAC نجح — الجلسة الآمنة مفتوحة")
    }

    // ---------- قراءة الملفات ----------

    private fun selectFilePlain(fid: Int) {
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x02, 0x0C, 0x02,
            (fid shr 8).toByte(), (fid and 0xFF).toByte())
        checkOk(transceive(apdu))
    }

    private fun readBinaryPlain(): ByteArray {
        val head = checkOk(transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x04)))
        val total = Tlv.totalLength(head)
        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        var offset = head.size
        while (offset < total) {
            val chunk = minOf(0x80, total - offset)
            val r = checkOk(transceive(byteArrayOf(
                0x00, 0xB0.toByte(), (offset shr 8).toByte(),
                (offset and 0xFF).toByte(), chunk.toByte())))
            out.write(r)
            offset += r.size
        }
        return out.toByteArray()
    }

    private fun secure() = sm ?: throw CardException("لازم تنفذ BAC أولاً")

    private fun selectFileSecure(fid: Int) {
        val s = secure()
        val fidBytes = byteArrayOf((fid shr 8).toByte(), (fid and 0xFF).toByte())
        val apdu = s.wrap(0x00, 0xA4, 0x02, 0x0C, fidBytes, null)
        s.unwrap(transceive(apdu))
    }

    private fun readBinarySecure(): ByteArray {
        val s = secure()
        val head = s.unwrap(transceive(s.wrap(0x00, 0xB0, 0x00, 0x00, null, 0x04)))
        val total = Tlv.totalLength(head)
        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        var offset = head.size
        while (offset < total) {
            val chunk = minOf(0x80, total - offset)
            val r = s.unwrap(transceive(
                s.wrap(0x00, 0xB0, offset shr 8, offset and 0xFF, null, chunk)))
            out.write(r)
            offset += r.size
        }
        return out.toByteArray()
    }

    /** يقرأ ملف كامل بعد BAC */
    fun readFile(fid: Int): ByteArray {
        selectFileSecure(fid)
        return readBinarySecure()
    }

    /** قراءة لا ترمي استثناء — ترجع null وتسجّل السبب. للملفات الاختيارية. */
    fun readFileOrNull(fid: Int, name: String): ByteArray? = try {
        val d = readFile(fid)
        logger("✔ $name مقروء (${d.size} بايت)")
        d
    } catch (e: Exception) {
        logger("✗ $name غير متاح: ${e.message}")
        null
    }
}