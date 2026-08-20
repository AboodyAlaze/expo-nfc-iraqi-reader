package expo.modules.nfciraqireader.nfc

object TextCodec {

    fun decode(b: ByteArray): String {
        if (b.isEmpty()) return ""

        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())
            return clean(String(b, 2, b.size - 2, Charsets.UTF_16BE))
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())
            return clean(String(b, 2, b.size - 2, Charsets.UTF_16LE))

        val u8 = clean(String(b, Charsets.UTF_8))
        if (arabicRatio(u8) > 0.15) return u8
        if (u8.isNotBlank() && isPlainAscii(u8)) return u8

        try {
            val fixed = clean(String(u8.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
            if (arabicRatio(fixed) > 0.15) return fixed
        } catch (_: Exception) {}

        try {
            val cp = clean(String(b, charset("windows-1256")))
            if (arabicRatio(cp) > 0.15) return cp
        } catch (_: Exception) {}

        if (b.size % 2 == 0) {
            val u16 = clean(String(b, Charsets.UTF_16BE))
            if (arabicRatio(u16) > 0.15) return u16
        }

        return u8
    }

    private fun arabicRatio(s: String): Double {
        val letters = s.count { !it.isWhitespace() }
        if (letters == 0) return 0.0
        val ar = s.count { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }
        return ar.toDouble() / letters
    }

    private fun isPlainAscii(s: String) = s.all { it.code in 0x20..0x7E }

    private fun clean(s: String) = s
        .replace("\uFFFD", "")
        .replace("<<", " ")
        .replace('<', ' ')
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex(" {2,}"), " ")
        .trim()
        .trim('_', '-', '.', ' ')

    /** حسب ICAO التاغ 5F0E يخزن: اللقب<<الاسم — يرجع (اللقب، الاسم) */
    fun splitName(raw: ByteArray): Pair<String, String> {
        val parts = splitFields(raw)
        return Pair(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
    }

    /** يفصل حقل متعدد القيم على «<<» ويفك كل جزء لوحده */
    fun splitFields(raw: ByteArray): List<String> {
        val s = String(raw, Charsets.ISO_8859_1)
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < s.length - 1) {
            if (s[i] == '<' && s[i + 1] == '<') {
                out.add(decode(raw.copyOfRange(start, i)))
                i += 2
                start = i
            } else i++
        }
        out.add(decode(raw.copyOfRange(start, raw.size)))
        return out.filter { it.isNotBlank() }
    }

    fun formatDate(d: String): String {
        val s = d.filter { it.isDigit() }
        return when (s.length) {
            6 -> {
                val yy = s.substring(0, 2).toInt()
                val year = if (yy > 40) 1900 + yy else 2000 + yy
                "${s.substring(4, 6)}/${s.substring(2, 4)}/$year"
            }
            8 -> "${s.substring(6, 8)}/${s.substring(4, 6)}/${s.substring(0, 4)}"
            else -> d
        }
    }

    fun sexArabic(s: String) = when (s.uppercase().trim()) {
        "M" -> "ذكر"
        "F" -> "أنثى"
        else -> s
    }
}