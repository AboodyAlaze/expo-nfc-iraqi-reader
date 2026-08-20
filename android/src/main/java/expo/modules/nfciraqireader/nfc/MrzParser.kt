package expo.modules.nfciraqireader.nfc

/** الحقول الثلاثة المطلوبة لفتح الشريحة */
data class MrzKeys(
    val documentNumber: String,
    val dateOfBirth: String,
    val dateOfExpiry: String
)

/**
 * ينتشل مفاتيح BAC من نص مقروء بالكاميرا.
 * يعتمد على أرقام التحقق للتأكد من صحة القراءة قبل ما يرجع شي.
 */
object MrzParser {

    /** يرجع null إذا ما لقى سطر MRZ صالح */
    fun extract(rawText: String): MrzKeys? {
        // ننظف: حروف كبيرة، ونشيل أي شي مو من أبجدية الـ MRZ
        val lines = rawText
            .uppercase()
            .lines()
            .map { it.replace(Regex("[^A-Z0-9<]"), "") }
            .filter { it.length >= 28 }

        // TD1: ثلاثة أسطر × 30
        for (line in lines) {
            tryTd1Line1(line)?.let { doc ->
                for (other in lines) {
                    if (other === line) continue
                    tryTd1Line2(other)?.let { (dob, exp) ->
                        return MrzKeys(doc, dob, exp)
                    }
                }
            }
        }

        // TD3 (جواز): سطرين × 44 — الحقول كلها بالسطر الثاني
        for (line in lines) {
            tryTd3Line2(line)?.let { return it }
        }

        return null
    }

    /**
     * السطر الأول بـ TD1: نوع(2) دولة(3) رقم(9) تحقق(1) اختياري(15)
     * مثال: I<IRQB12345678 4 ...
     */
    private fun tryTd1Line1(line: String): String? {
        if (line.length < 15) return null
        if (line[0] != 'I' && line[0] != 'A' && line[0] != 'C') return null

        val doc = line.substring(5, 14)
        val check = line.getOrNull(14) ?: return null
        return if (MrzKey.checkDigit(doc) == check) doc else null
    }

    /**
     * السطر الثاني بـ TD1: ميلاد(6) تحقق(1) جنس(1) انتهاء(6) تحقق(1) جنسية(3)
     */
    private fun tryTd1Line2(line: String): Pair<String, String>? {
        if (line.length < 15) return null

        val dob = line.substring(0, 6)
        val dobCheck = line[6]
        val sex = line[7]
        val exp = line.substring(8, 14)
        val expCheck = line[14]

        if (!dob.all { it.isDigit() } || !exp.all { it.isDigit() }) return null
        if (sex != 'M' && sex != 'F' && sex != '<') return null
        if (MrzKey.checkDigit(dob) != dobCheck) return null
        if (MrzKey.checkDigit(exp) != expCheck) return null

        return Pair(dob, exp)
    }

    /**
     * السطر الثاني بـ TD3: رقم(9) تحقق(1) جنسية(3) ميلاد(6) تحقق(1) جنس(1) انتهاء(6) تحقق(1)
     */
    private fun tryTd3Line2(line: String): MrzKeys? {
        if (line.length < 28) return null

        val doc = line.substring(0, 9)
        val docCheck = line[9]
        val dob = line.substring(13, 19)
        val dobCheck = line[19]
        val exp = line.substring(21, 27)
        val expCheck = line[27]

        if (!dob.all { it.isDigit() } || !exp.all { it.isDigit() }) return null
        if (MrzKey.checkDigit(doc) != docCheck) return null
        if (MrzKey.checkDigit(dob) != dobCheck) return null
        if (MrzKey.checkDigit(exp) != expCheck) return null

        return MrzKeys(doc.replace("<", ""), dob, exp)
    }
}