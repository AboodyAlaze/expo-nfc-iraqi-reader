package expo.modules.nfciraqireader.nfc

object Tlv {

    data class Entry(val tag: Int, val value: ByteArray)

    /** الطول الكلي للملف من أول 4 بايت — بسيطة ومباشرة، لا تستدعي أي شي ثاني */
    fun totalLength(head: ByteArray): Int {
        var i = if ((head[0].toInt() and 0x1F) == 0x1F) 2 else 1
        val first = head[i].toInt() and 0xFF
        return if (first < 0x80) i + 1 + first
        else {
            val n = first and 0x7F
            var len = 0
            for (j in 1..n) len = (len shl 8) or (head[i + j].toInt() and 0xFF)
            i + 1 + n + len
        }
    }

    /** بحث نمطي — هي اللي كانت شغالة، نخليها الافتراضية */
    fun findTag(data: ByteArray, tag: Int): ByteArray? {
        val tagBytes = if (tag > 0xFF)
            byteArrayOf((tag shr 8).toByte(), (tag and 0xFF).toByte())
        else byteArrayOf(tag.toByte())

        var i = 0
        while (i < data.size - tagBytes.size) {
            var match = true
            for (k in tagBytes.indices) if (data[i + k] != tagBytes[k]) { match = false; break }
            if (match) {
                var j = i + tagBytes.size
                if (j >= data.size) return null
                val first = data[j].toInt() and 0xFF
                val len: Int
                if (first < 0x80) { len = first; j += 1 }
                else {
                    val n = first and 0x7F
                    if (n == 0 || n > 3 || j + n >= data.size) { i++; continue }
                    var l = 0
                    for (k in 1..n) l = (l shl 8) or (data[j + k].toInt() and 0xFF)
                    len = l; j += 1 + n
                }
                if (j + len <= data.size) return data.copyOfRange(j, j + len)
            }
            i++
        }
        return null
    }

    /** تحليل TLV حقيقي — يُستعمل فقط على ملف مكتمل بعد ما ينقرأ */
    fun walk(data: ByteArray): List<Entry> {
        val out = mutableListOf<Entry>()
        var i = 0
        while (i < data.size - 1) {
            var tag = data[i].toInt() and 0xFF
            var j = i + 1
            if (tag == 0x00 || tag == 0xFF) { i++; continue }
            if ((tag and 0x1F) == 0x1F) {
                if (j >= data.size) break
                tag = (tag shl 8) or (data[j].toInt() and 0xFF)
                j++
            }
            if (j >= data.size) break
            val first = data[j].toInt() and 0xFF
            val len: Int
            if (first < 0x80) { len = first; j++ }
            else {
                val n = first and 0x7F
                if (n == 0 || n > 3 || j + n >= data.size) break
                var l = 0
                for (k in 1..n) l = (l shl 8) or (data[j + k].toInt() and 0xFF)
                len = l
                j += 1 + n
            }
            if (len < 0 || j + len > data.size) break
            out.add(Entry(tag, data.copyOfRange(j, j + len)))
            i = j + len
        }
        return out
    }

    fun map(data: ByteArray, depth: Int = 3): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        fun rec(d: ByteArray, lvl: Int) {
            for (e in walk(d)) {
                if (!out.containsKey(e.tag)) out[e.tag] = e.value
                val constructed = e.tag <= 0xFF && (e.tag and 0x20) != 0
                if (lvl > 0 && constructed) rec(e.value, lvl - 1)
            }
        }
        rec(data, depth)
        return out
    }

    /** يجرب التحليل الصحيح، وإذا ما لكى يرجع للبحث النمطي */
    fun findTagSmart(data: ByteArray, tag: Int): ByteArray? =
        map(data)[tag] ?: findTag(data, tag)
}