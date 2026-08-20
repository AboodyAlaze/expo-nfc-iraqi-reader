import Foundation

/// تغليف/فك تغليف أوامر APDU بعد نجاح BAC
final class SecureMessaging {

    private let ksEnc: Data
    private let ksMac: Data
    private var ssc: UInt64

    init(ksEnc: Data, ksMac: Data, ssc: UInt64) {
        self.ksEnc = ksEnc
        self.ksMac = ksMac
        self.ssc = ssc
    }

    private func sscBytes() -> Data {
        var b = Data(count: 8)
        var v = ssc
        for i in stride(from: 7, through: 0, by: -1) {
            b[i] = UInt8(v & 0xFF)
            v >>= 8
        }
        return b
    }

    /// ترميز الطول حسب BER
    private func encodeLen(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        if n < 0x100 { return Data([0x81, UInt8(n)]) }
        return Data([0x82, UInt8(n >> 8), UInt8(n & 0xFF)])
    }

    /// يرجع محتوى APDU المغلّف (بدون CLA/INS/P1/P2 — نرجعهم منفصلين)
    func wrap(cla: UInt8, ins: UInt8, p1: UInt8, p2: UInt8,
              data: Data?, le: Int?) throws -> (header: Data, body: Data) {

        let maskedCla = cla | 0x0C
        let header = Data([maskedCla, ins, p1, p2])
        let paddedHeader = Crypto.pad(header)

        var do87 = Data()
        if let d = data, !d.isEmpty {
            let enc = try Crypto.tdesEncrypt(key16: ksEnc, data: Crypto.pad(d))
            let body = Data([0x01]) + enc
            do87 = Data([0x87]) + encodeLen(body.count) + body
        }

        var do97 = Data()
        if let l = le {
            do97 = Data([0x97, 0x01, UInt8(l)])
        }

        ssc &+= 1
        let macInput = Crypto.pad(sscBytes() + paddedHeader + do87 + do97)
        let cc = try Crypto.retailMac(key16: ksMac, paddedData: macInput)
        let do8e = Data([0x8E, 0x08]) + cc

        return (header, do87 + do97 + do8e)
    }

    /// يفك رد البطاقة ويتحقق من الـ MAC
    func unwrap(_ response: Data, sw1: UInt8, sw2: UInt8) throws -> Data {
        let body = [UInt8](response)

        var i = 0
        var do87 = Data()
        var do99 = Data()
        var do8e = Data()
        var encData: Data? = nil

        while i < body.count - 1 {
            let tag = body[i]

            var j = i + 1
            let first = Int(body[j])
            let len: Int
            if first < 0x80 {
                len = first
                j += 1
            } else {
                let n = first & 0x7F
                guard n > 0, n <= 3, j + n < body.count else { break }
                var l = 0
                for k in 1...n { l = (l << 8) | Int(body[j + k]) }
                len = l
                j += 1 + n
            }
            guard j + len <= body.count else { break }

            let value = Data(body[j..<(j + len)])
            let full = Data(body[i..<(j + len)])

            switch tag {
            case 0x87:
                do87 = full
                encData = Data(value.dropFirst())   // نتجاوز بايت 0x01
            case 0x99:
                do99 = full
            case 0x8E:
                do8e = value
            default:
                break
            }
            i = j + len
        }

        // إذا البطاقة ما رجّعت DO99، نبنيه من الحالة المستلمة
        if do99.isEmpty {
            do99 = Data([0x99, 0x02, sw1, sw2])
        }

        ssc &+= 1
        let expected = try Crypto.retailMac(key16: ksMac,
                                            paddedData: Crypto.pad(sscBytes() + do87 + do99))
        guard expected == do8e else { throw CardError.macMismatch }

        let sw = (UInt16(do99[do99.startIndex + 2]) << 8) | UInt16(do99[do99.startIndex + 3])
        guard sw == 0x9000 else { throw CardError.status(sw) }

        guard let e = encData else { return Data() }
        return try Crypto.unpad(try Crypto.tdesDecrypt(key16: ksEnc, data: e))
    }
}
