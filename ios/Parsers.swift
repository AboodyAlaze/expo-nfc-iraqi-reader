import Foundation

struct IdData {
    // من DG1 (الـ MRZ)
    var documentCode = ""
    var issuingState = ""
    var documentNumber = ""
    var surname = ""
    var givenNames = ""
    var nationality = ""
    var dateOfBirth = ""
    var sex = ""
    var dateOfExpiry = ""
    var nationalNumber = ""
    var rawMrz = ""

    // من DG2
    var faceImage: Data? = nil
    var faceNote = ""

    // من DG11
    var fullNameArabic = ""
    var surnameArabic = ""
    var motherName = ""
    var grandfatherName = ""
    var placeOfBirth = ""
    var personalNumber = ""

    // من DG12
    var issuingAuthority = ""
    var dateOfIssue = ""

    var availableDataGroups: [Int] = []
}

// MARK: - TLV

enum Tlv {

    /// الطول الكلي للملف من أول 4 بايت
    static func totalLength(_ head: Data) -> Int {
        let b = [UInt8](head)
        guard b.count >= 2 else { return b.count }
        var i = (b[0] & 0x1F) == 0x1F ? 2 : 1
        guard i < b.count else { return b.count }
        let first = Int(b[i])
        if first < 0x80 { return i + 1 + first }
        let n = first & 0x7F
        guard n > 0, n <= 3, i + n < b.count else { return b.count }
        var len = 0
        for j in 1...n { len = (len << 8) | Int(b[i + j]) }
        i += n
        return i + 1 + len
    }

    /// بحث عن تاغ (بايت أو بايتين) وإرجاع محتواه
    static func findTag(_ data: Data, _ tag: Int) -> Data? {
        let b = [UInt8](data)
        let tagBytes: [UInt8] = tag > 0xFF
            ? [UInt8(tag >> 8), UInt8(tag & 0xFF)]
            : [UInt8(tag)]

        var i = 0
        while i + tagBytes.count < b.count {
            var match = true
            for k in 0..<tagBytes.count where b[i + k] != tagBytes[k] { match = false; break }
            if match {
                var j = i + tagBytes.count
                guard j < b.count else { return nil }
                let first = Int(b[j])
                var len = 0
                if first < 0x80 {
                    len = first
                    j += 1
                } else {
                    let n = first & 0x7F
                    guard n > 0, n <= 3, j + n < b.count else { i += 1; continue }
                    for k in 1...n { len = (len << 8) | Int(b[j + k]) }
                    j += 1 + n
                }
                if j + len <= b.count { return Data(b[j..<(j + len)]) }
            }
            i += 1
        }
        return nil
    }
}

// MARK: - EF.COM

enum ComParser {

    private static let tagToDg: [UInt8: Int] = [
        0x61: 1, 0x75: 2, 0x63: 3, 0x76: 4, 0x65: 5, 0x66: 6,
        0x67: 7, 0x68: 8, 0x69: 9, 0x6A: 10, 0x6B: 11, 0x6C: 12,
        0x6D: 13, 0x6E: 14, 0x6F: 15, 0x70: 16
    ]

    static func dataGroups(_ efCom: Data) -> [Int] {
        guard let list = Tlv.findTag(efCom, 0x5C) else { return [] }
        return [UInt8](list).compactMap { tagToDg[$0] }.sorted()
    }
}

// MARK: - النصوص

enum TextCodec {

    static func decode(_ b: Data) -> String {
        if b.isEmpty { return "" }

        let bytes = [UInt8](b)
        if bytes.count >= 2, bytes[0] == 0xFE, bytes[1] == 0xFF,
           let s = String(data: Data(bytes.dropFirst(2)), encoding: .utf16BigEndian) {
            return clean(s)
        }

        let u8 = clean(String(decoding: b, as: UTF8.self))
        if arabicRatio(u8) > 0.15 { return u8 }
        if !u8.isEmpty && u8.allSatisfy({ $0.isASCII }) { return u8 }

        // نص مرمّز مرتين
        if let raw = u8.data(using: .isoLatin1) {
            let fixed = clean(String(decoding: raw, as: UTF8.self))
            if arabicRatio(fixed) > 0.15 { return fixed }
        }

        // windows-1256
        if let s = String(data: b, encoding: String.Encoding(
            rawValue: CFStringConvertEncodingToNSStringEncoding(
                CFStringEncoding(CFStringEncodings.windowsArabic.rawValue)))) {
            if arabicRatio(s) > 0.15 { return clean(s) }
        }

        if b.count % 2 == 0, let s = String(data: b, encoding: .utf16BigEndian) {
            if arabicRatio(s) > 0.15 { return clean(s) }
        }

        return u8
    }

    private static func arabicRatio(_ s: String) -> Double {
        let letters = s.filter { !$0.isWhitespace }.count
        if letters == 0 { return 0 }
        let ar = s.unicodeScalars.filter { (0x0600...0x06FF).contains($0.value) }.count
        return Double(ar) / Double(letters)
    }

    private static func clean(_ s: String) -> String {
        var out = s.replacingOccurrences(of: "\u{FFFD}", with: "")
        out = out.replacingOccurrences(of: "<<", with: " ")
        out = out.replacingOccurrences(of: "<", with: " ")
        out = String(out.unicodeScalars.map { $0.value < 0x20 ? " " : Character($0) })
        while out.contains("  ") { out = out.replacingOccurrences(of: "  ", with: " ") }
        return out.trimmingCharacters(in: CharacterSet(charactersIn: " _-."))
    }

    /// YYMMDD أو YYYYMMDD → DD/MM/YYYY
    static func formatDate(_ d: String) -> String {
        let s = d.filter { $0.isNumber }
        let a = Array(s)
        if a.count == 6 {
            let yy = Int(String(a[0...1])) ?? 0
            let year = yy > 40 ? 1900 + yy : 2000 + yy
            return "\(String(a[4...5]))/\(String(a[2...3]))/\(year)"
        }
        if a.count == 8 {
            return "\(String(a[6...7]))/\(String(a[4...5]))/\(String(a[0...3]))"
        }
        return d
    }

    static func sexArabic(_ s: String) -> String {
        switch s.uppercased().trimmingCharacters(in: .whitespaces) {
        case "M": return "ذكر"
        case "F": return "أنثى"
        default: return s
        }
    }
}

// MARK: - DG1

enum Dg1Parser {

    static func parse(_ dg1: Data) -> IdData {
        guard let mrzBytes = Tlv.findTag(dg1, 0x5F1F) else { return IdData() }
        let mrz = String(decoding: mrzBytes, as: UTF8.self)
        var d = IdData()
        d.rawMrz = mrz

        let a = Array(mrz)
        guard a.count == 90 else { return d }   // TD1: 3 أسطر × 30

        func clean(_ s: String) -> String {
            s.replacingOccurrences(of: "<", with: " ").trimmingCharacters(in: .whitespaces)
        }

        let l1 = String(a[0..<30])
        let l2 = String(a[30..<60])
        let l3 = String(a[60..<90])

        let c1 = Array(l1), c2 = Array(l2)

        d.documentCode   = clean(String(c1[0..<2]))
        d.issuingState   = clean(String(c1[2..<5]))
        d.documentNumber = clean(String(c1[5..<14]))
        d.nationalNumber = clean(String(c1[15..<30]))

        d.dateOfBirth  = String(c2[0..<6])
        d.sex          = String(c2[7])
        d.dateOfExpiry = String(c2[8..<14])
        d.nationality  = clean(String(c2[15..<18]))

        let parts = l3.components(separatedBy: "<<")
        d.surname    = clean(parts.first ?? "")
        d.givenNames = clean(parts.count > 1 ? parts[1] : "")

        return d
    }
}

// MARK: - DG11 / DG12 / DG2

enum Dg11Parser {

    static func merge(_ dg11: Data, into base: IdData) -> IdData {
        var d = base
        guard let raw = Tlv.findTag(dg11, 0x5F0E) else { return d }
        let text = TextCodec.decode(raw)

        let parts = text
            .components(separatedBy: CharacterSet(charactersIn: "<_|^~\\/0123456789"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        // الجزء الأول: الاسم + اللقب (آخر كلمة هي اللقب)
        let b1 = (parts.first ?? "").split(separator: " ").map(String.init)
        if b1.count > 1 {
            d.surnameArabic = b1.last ?? ""
            d.fullNameArabic = b1.dropLast().joined(separator: " ")
        } else {
            d.fullNameArabic = b1.joined(separator: " ")
        }

        // الجزء الثاني: الأم + الجد
        if parts.count > 1 {
            let b2 = parts[1].split(separator: " ").map(String.init)
            d.motherName = b2.first ?? ""
            d.grandfatherName = b2.dropFirst().joined(separator: " ")
        }

        if let p = Tlv.findTag(dg11, 0x5F11) { d.placeOfBirth = TextCodec.decode(p) }
        if let n = Tlv.findTag(dg11, 0x5F10) { d.personalNumber = TextCodec.decode(n) }

        return d
    }
}

enum Dg12Parser {
    static func merge(_ dg12: Data, into base: IdData) -> IdData {
        var d = base
        if let a = Tlv.findTag(dg12, 0x5F19) { d.issuingAuthority = TextCodec.decode(a) }
        if let i = Tlv.findTag(dg12, 0x5F26) { d.dateOfIssue = TextCodec.decode(i) }
        return d
    }
}

enum Dg2Parser {

    static func extractFace(_ dg2: Data) -> (Data?, String) {
        if let i = indexOf(dg2, [0xFF, 0xD8, 0xFF]) {
            return (Data(dg2[i...]), "JPEG")
        }
        if let i = indexOf(dg2, [0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20]) {
            return (Data(dg2[i...]), "JPEG2000")
        }
        if let i = indexOf(dg2, [0xFF, 0x4F, 0xFF, 0x51]) {
            return (Data(dg2[i...]), "JPEG2000-codestream")
        }
        return (nil, "صيغة غير معروفة (\(dg2.count) بايت)")
    }

    private static func indexOf(_ data: Data, _ pattern: [UInt8]) -> Int? {
        let b = [UInt8](data)
        guard b.count >= pattern.count else { return nil }
        for i in 0...(b.count - pattern.count) {
            var ok = true
            for j in 0..<pattern.count where b[i + j] != pattern[j] { ok = false; break }
            if ok { return i }
        }
        return nil
    }
}
