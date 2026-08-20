import Foundation
import CryptoKit

enum MrzKey {

    private static let weights = [7, 3, 1]

    static func checkDigit(_ input: String) -> Character {
        var sum = 0
        for (i, c) in input.enumerated() {
            let v: Int
            if c.isNumber { v = c.wholeNumberValue ?? 0 }
            else if c == "<" { v = 0 }
            else if let a = c.asciiValue, c.isLetter { v = Int(a) - 65 + 10 }
            else { v = 0 }
            sum += v * weights[i % 3]
        }
        return Character(String(sum % 10))
    }

    static func mrzInformation(doc: String, dob: String, expiry: String) -> String {
        var d = doc.uppercased()
        while d.count < 9 { d += "<" }
        return d + String(checkDigit(d)) + dob + String(checkDigit(dob))
             + expiry + String(checkDigit(expiry))
    }

    /// يرجع (Kenc, Kmac)
    static func deriveBacKeys(doc: String, dob: String, expiry: String) -> (Data, Data) {
        let info = mrzInformation(doc: doc, dob: dob, expiry: expiry)
        let seed = Data(Insecure.SHA1.hash(data: Data(info.utf8)).prefix(16))
        return (kdf(seed: seed, counter: 1), kdf(seed: seed, counter: 2))
    }

    static func kdf(seed: Data, counter: UInt8) -> Data {
        let d = seed + Data([0, 0, 0, counter])
        var key = Array(Insecure.SHA1.hash(data: d).prefix(16))
        for i in key.indices {
            let ones = (key[i] & 0xFE).nonzeroBitCount
            key[i] = (ones % 2 == 0) ? (key[i] | 0x01) : (key[i] & 0xFE)
        }
        return Data(key)
    }
}
