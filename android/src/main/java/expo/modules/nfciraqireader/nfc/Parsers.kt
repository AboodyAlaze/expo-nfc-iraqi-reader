package expo.modules.nfciraqireader.nfc

data class IdData(
    val documentCode: String = "",
    val issuingState: String = "",
    val documentNumber: String = "",
    val surname: String = "",
    val givenNames: String = "",
    val nationality: String = "",
    val dateOfBirth: String = "",
    val sex: String = "",
    val dateOfExpiry: String = "",
    val nationalNumber: String = "",
    val optionalData2: String = "",
    val rawMrz: String = "",

    val faceImage: ByteArray? = null,
    val faceNote: String = "",

    val fullNameArabic: String = "",
    val surnameArabic: String = "",
    val givenNamesArabic: String = "",
    val motherName: String = "",
    val grandfatherName: String = "",
    val otherNames: String = "",
    val personalNumber: String = "",
    val fullDateOfBirth: String = "",
    val placeOfBirth: String = "",
    val address: String = "",
    val telephone: String = "",
    val profession: String = "",
    val title: String = "",
    val personalSummary: String = "",

    val issuingAuthority: String = "",
    val dateOfIssue: String = "",

    val availableDataGroups: List<Int> = emptyList(),
    val extraStrings: List<String> = emptyList()
)

object ComParser {

    private val TAG_TO_DG = mapOf(
        0x61 to 1, 0x75 to 2, 0x63 to 3, 0x76 to 4, 0x65 to 5, 0x66 to 6,
        0x67 to 7, 0x68 to 8, 0x69 to 9, 0x6A to 10, 0x6B to 11, 0x6C to 12,
        0x6D to 13, 0x6E to 14, 0x6F to 15, 0x70 to 16
    )

    fun dataGroups(efCom: ByteArray): List<Int> {
        val list = Tlv.findTag(efCom, 0x5C) ?: return emptyList()
        return list.toList().mapNotNull { TAG_TO_DG[it.toInt() and 0xFF] }.sorted()
    }
}

object Dg1Parser {

    fun parse(dg1: ByteArray): IdData {
        val mrzBytes = Tlv.findTag(dg1, 0x5F1F) ?: throw CardException("ما لكيت MRZ داخل DG1")
        val mrz = String(mrzBytes, Charsets.US_ASCII)
        return when (mrz.length) {
            90 -> parseTd1(mrz)
            88 -> parseTd3(mrz)
            72 -> parseTd2(mrz)
            else -> IdData(rawMrz = mrz)
        }
    }

    private fun clean(s: String) = s.replace('<', ' ').trim()

    private fun names(field: String): Pair<String, String> {
        val parts = field.split("<<")
        return Pair(
            clean(parts.getOrElse(0) { "" }),
            clean(parts.getOrElse(1) { "" }.replace("<", " "))
        )
    }

    private fun parseTd1(mrz: String): IdData {
        val l1 = mrz.substring(0, 30)
        val l2 = mrz.substring(30, 60)
        val l3 = mrz.substring(60, 90)
        val (surname, given) = names(l3)
        return IdData(
            documentCode = clean(l1.substring(0, 2)),
            issuingState = clean(l1.substring(2, 5)),
            documentNumber = clean(l1.substring(5, 14)),
            nationalNumber = clean(l1.substring(15, 30)),
            dateOfBirth = l2.substring(0, 6),
            sex = l2.substring(7, 8),
            dateOfExpiry = l2.substring(8, 14),
            nationality = clean(l2.substring(15, 18)),
            optionalData2 = clean(l2.substring(18, 29)),
            surname = surname,
            givenNames = given,
            rawMrz = mrz
        )
    }

    private fun parseTd2(mrz: String): IdData {
        val l1 = mrz.substring(0, 36)
        val l2 = mrz.substring(36, 72)
        val (surname, given) = names(l1.substring(5, 36))
        return IdData(
            documentCode = clean(l1.substring(0, 2)),
            issuingState = clean(l1.substring(2, 5)),
            surname = surname, givenNames = given,
            documentNumber = clean(l2.substring(0, 9)),
            nationality = clean(l2.substring(10, 13)),
            dateOfBirth = l2.substring(13, 19),
            sex = l2.substring(20, 21),
            dateOfExpiry = l2.substring(21, 27),
            rawMrz = mrz
        )
    }

    private fun parseTd3(mrz: String): IdData {
        val l1 = mrz.substring(0, 44)
        val l2 = mrz.substring(44, 88)
        val (surname, given) = names(l1.substring(5, 44))
        return IdData(
            documentCode = clean(l1.substring(0, 2)),
            issuingState = clean(l1.substring(2, 5)),
            surname = surname, givenNames = given,
            documentNumber = clean(l2.substring(0, 9)),
            nationality = clean(l2.substring(10, 13)),
            dateOfBirth = l2.substring(13, 19),
            sex = l2.substring(20, 21),
            dateOfExpiry = l2.substring(21, 27),
            nationalNumber = clean(l2.substring(28, 42)),
            rawMrz = mrz
        )
    }
}

object Dg11Parser {

    fun merge(dg11: ByteArray, base: IdData): IdData {
        val nameRaw = Tlv.findTag(dg11, 0x5F0E) ?: return base
        val text = TextCodec.decode(nameRaw)

        val parts = text
            .split(Regex("[<_|^~\\\\/]+|\\d+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // الجزء الأول: الاسم الكامل + اللقب (آخر كلمة هي اللقب)
        val block1 = parts.getOrElse(0) { "" }.split(Regex("\\s+")).filter { it.isNotBlank() }
        val surname = if (block1.size > 1) block1.last() else ""
        val fullName = if (block1.size > 1)
            block1.dropLast(1).joinToString(" ") else block1.joinToString(" ")

        // الجزء الثاني: اسم الأم + اسم الجد
        val block2 = parts.getOrElse(1) { "" }.split(Regex("\\s+")).filter { it.isNotBlank() }
        val mother = block2.getOrElse(0) { "" }
        val grandpa = block2.drop(1).joinToString(" ")

        return base.copy(
            fullNameArabic = fullName,
            surnameArabic = surname,
            motherName = mother,
            grandfatherName = grandpa
        )
    }
}

object Dg12Parser {
    fun merge(dg12: ByteArray, base: IdData): IdData {
        val tags = HashMap<Int, ByteArray>()
        Tlv.findTag(dg12, 0x5F19)?.let { tags[0x5F19] = it }
        Tlv.findTag(dg12, 0x5F26)?.let { tags[0x5F26] = it }
        fun txt(tag: Int) = tags[tag]?.let { TextCodec.decode(it) } ?: ""
        return base.copy(
            issuingAuthority = txt(0x5F19),
            dateOfIssue = txt(0x5F26)
        )
    }
}

object Dg13Parser {

    fun extractStrings(data: ByteArray, minLen: Int = 3): List<String> {
        val out = mutableListOf<String>()
        var buf = mutableListOf<Byte>()
        for (b in data) {
            val v = b.toInt() and 0xFF
            val printable = (v in 0x20..0x7E) || v >= 0xC0 || (v in 0x80..0xBF && buf.isNotEmpty())
            if (printable) buf.add(b) else {
                flush(buf, out, minLen); buf = mutableListOf()
            }
        }
        flush(buf, out, minLen)
        return out.distinct().take(40)
    }

    private fun flush(buf: List<Byte>, out: MutableList<String>, minLen: Int) {
        if (buf.size < minLen) return
        val s = TextCodec.decode(buf.toByteArray())
        if (s.length >= minLen && s.any { it.isLetterOrDigit() }) out.add(s)
    }
}

object Dg2Parser {

    fun extractFace(dg2: ByteArray): Pair<ByteArray?, String> {
        indexOf(dg2, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.let {
            return Pair(dg2.copyOfRange(it, dg2.size), "JPEG")
        }
        indexOf(dg2, byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20))?.let {
            return Pair(dg2.copyOfRange(it, dg2.size), "JPEG2000")
        }
        indexOf(dg2, byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51))?.let {
            return Pair(dg2.copyOfRange(it, dg2.size), "JPEG2000-codestream")
        }
        return Pair(null, "صيغة غير معروفة (حجم DG2 = ${dg2.size} بايت)")
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }
}