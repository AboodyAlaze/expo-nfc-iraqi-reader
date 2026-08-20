import Foundation
import CommonCrypto

enum CardError: Error, LocalizedError {
    case crypto
    case badPadding
    case bacFailed(String)
    case macMismatch
    case status(UInt16)
    case noApplet
    case noTag

    var errorDescription: String? {
        switch self {
        case .crypto: return "خطأ بالتشفير"
        case .badPadding: return "padding غير صحيح"
        case .bacFailed(let m): return "فشل BAC: \(m)"
        case .macMismatch: return "فشل التحقق من الـ MAC"
        case .status(let s): return String(format: "البطاقة ردت بحالة %04X", s)
        case .noApplet: return "ما لكيت تطبيق eMRTD على الشريحة"
        case .noTag: return "ما وصلت البطاقة"
        }
    }
}

enum Crypto {

    static func pad(_ data: Data) -> Data {
        var out = data
        out.append(0x80)
        let rem = out.count % 8
        if rem != 0 { out.append(Data(repeating: 0, count: 8 - rem)) }
        return out
    }

    static func unpad(_ data: Data) throws -> Data {
        let bytes = [UInt8](data)
        var i = bytes.count - 1
        while i >= 0 && bytes[i] == 0x00 { i -= 1 }
        guard i >= 0, bytes[i] == 0x80 else { throw CardError.badPadding }
        return Data(bytes.prefix(i))
    }

    /// مفتاح 16 بايت → 24 بايت (K1|K2|K1)
    private static func expand(_ k16: Data) -> Data {
        Data(k16) + Data(k16.prefix(8))
    }

    static func tdesEncrypt(key16: Data, data: Data,
                            iv: Data = Data(repeating: 0, count: 8)) throws -> Data {
        try des3(key16: key16, data: data, iv: iv, op: CCOperation(kCCEncrypt))
    }

    static func tdesDecrypt(key16: Data, data: Data,
                            iv: Data = Data(repeating: 0, count: 8)) throws -> Data {
        try des3(key16: key16, data: data, iv: iv, op: CCOperation(kCCDecrypt))
    }

    private static func des3(key16: Data, data: Data, iv: Data, op: CCOperation) throws -> Data {
        let key = expand(key16)
        let capacity = data.count + kCCBlockSize3DES
        var out = Data(count: capacity)
        var moved = 0

        let status = out.withUnsafeMutableBytes { outPtr -> Int32 in
            data.withUnsafeBytes { dataPtr in
                key.withUnsafeBytes { keyPtr in
                    iv.withUnsafeBytes { ivPtr in
                        CCCrypt(op,
                                CCAlgorithm(kCCAlgorithm3DES),
                                CCOptions(0),
                                keyPtr.baseAddress, key.count,
                                ivPtr.baseAddress,
                                dataPtr.baseAddress, data.count,
                                outPtr.baseAddress, capacity,
                                &moved)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw CardError.crypto }
        return Data(out.prefix(moved))
    }

    /// DES بلوك واحد بـ ECB — أساس الـ Retail MAC
    private static func desECB(key8: Data, data: Data, op: CCOperation) throws -> Data {
        let capacity = data.count + kCCBlockSizeDES
        var out = Data(count: capacity)
        var moved = 0

        let status = out.withUnsafeMutableBytes { outPtr -> Int32 in
            data.withUnsafeBytes { dataPtr in
                key8.withUnsafeBytes { keyPtr in
                    CCCrypt(op,
                            CCAlgorithm(kCCAlgorithmDES),
                            CCOptions(kCCOptionECBMode),
                            keyPtr.baseAddress, key8.count,
                            nil,
                            dataPtr.baseAddress, data.count,
                            outPtr.baseAddress, capacity,
                            &moved)
                }
            }
        }
        guard status == kCCSuccess else { throw CardError.crypto }
        return Data(out.prefix(moved))
    }

    private static func desCBC(key8: Data, data: Data) throws -> Data {
        let capacity = data.count + kCCBlockSizeDES
        var out = Data(count: capacity)
        var moved = 0
        let iv = Data(repeating: 0, count: 8)

        let status = out.withUnsafeMutableBytes { outPtr -> Int32 in
            data.withUnsafeBytes { dataPtr in
                key8.withUnsafeBytes { keyPtr in
                    iv.withUnsafeBytes { ivPtr in
                        CCCrypt(CCOperation(kCCEncrypt),
                                CCAlgorithm(kCCAlgorithmDES),
                                CCOptions(0),
                                keyPtr.baseAddress, key8.count,
                                ivPtr.baseAddress,
                                dataPtr.baseAddress, data.count,
                                outPtr.baseAddress, capacity,
                                &moved)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw CardError.crypto }
        return Data(out.prefix(moved))
    }

    /// Retail MAC — ISO 9797-1 Algorithm 3. البيانات لازم تكون مبطّنة مسبقاً.
    static func retailMac(key16: Data, paddedData: Data) throws -> Data {
        guard paddedData.count % 8 == 0 else { throw CardError.crypto }
        let ka = Data(key16.prefix(8))
        let kb = Data(key16.suffix(8))

        let cbc = try desCBC(key8: ka, data: paddedData)
        let last = Data(cbc.suffix(8))

        let t = try desECB(key8: kb, data: last, op: CCOperation(kCCDecrypt))
        return try desECB(key8: ka, data: t, op: CCOperation(kCCEncrypt))
    }

    static func randomBytes(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }

    static func xor(_ a: Data, _ b: Data) -> Data {
        Data(zip(a, b).map { $0 ^ $1 })
    }
}

extension Data {
    var hex: String { map { String(format: "%02X", $0) }.joined(separator: " ") }
}
