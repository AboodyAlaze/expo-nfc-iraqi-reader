import Foundation

/// الحقول الثلاثة المطلوبة لفتح الشريحة
struct MrzKeys {
  let documentNumber: String
  let dateOfBirth: String
  let dateOfExpiry: String
}

/// ينتشل مفاتيح BAC من نص مقروء بالكاميرا.
/// يعتمد على أرقام التحقق للتأكد من صحة القراءة قبل ما يرجع شي.
enum MrzParser {

  static func extract(_ rawText: String) -> MrzKeys? {
    let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<")

    let lines = rawText
      .uppercased()
      .components(separatedBy: .newlines)
      .map { line in
        String(line.unicodeScalars.filter { allowed.contains($0) })
      }
      .filter { $0.count >= 28 }

    // TD1: ثلاثة أسطر × 30
    for line in lines {
      guard let doc = tryTd1Line1(line) else { continue }
      for other in lines where other != line {
        if let (dob, exp) = tryTd1Line2(other) {
          return MrzKeys(documentNumber: doc, dateOfBirth: dob, dateOfExpiry: exp)
        }
      }
    }

    // TD3 (جواز): سطرين × 44
    for line in lines {
      if let keys = tryTd3Line2(line) { return keys }
    }

    return nil
  }

  /// السطر الأول بـ TD1: نوع(2) دولة(3) رقم(9) تحقق(1) اختياري(15)
  private static func tryTd1Line1(_ line: String) -> String? {
    let a = Array(line)
    guard a.count >= 15 else { return nil }
    guard a[0] == "I" || a[0] == "A" || a[0] == "C" else { return nil }

    let doc = String(a[5..<14])
    let check = a[14]
    return MrzKey.checkDigit(doc) == check ? doc : nil
  }

  /// السطر الثاني بـ TD1: ميلاد(6) تحقق(1) جنس(1) انتهاء(6) تحقق(1)
  private static func tryTd1Line2(_ line: String) -> (String, String)? {
    let a = Array(line)
    guard a.count >= 15 else { return nil }

    let dob = String(a[0..<6])
    let dobCheck = a[6]
    let sex = a[7]
    let exp = String(a[8..<14])
    let expCheck = a[14]

    guard dob.allSatisfy({ $0.isNumber }), exp.allSatisfy({ $0.isNumber }) else { return nil }
    guard sex == "M" || sex == "F" || sex == "<" else { return nil }
    guard MrzKey.checkDigit(dob) == dobCheck else { return nil }
    guard MrzKey.checkDigit(exp) == expCheck else { return nil }

    return (dob, exp)
  }

  /// السطر الثاني بـ TD3: رقم(9) تحقق(1) جنسية(3) ميلاد(6) تحقق(1) جنس(1) انتهاء(6) تحقق(1)
  private static func tryTd3Line2(_ line: String) -> MrzKeys? {
    let a = Array(line)
    guard a.count >= 28 else { return nil }

    let doc = String(a[0..<9])
    let docCheck = a[9]
    let dob = String(a[13..<19])
    let dobCheck = a[19]
    let exp = String(a[21..<27])
    let expCheck = a[27]

    guard dob.allSatisfy({ $0.isNumber }), exp.allSatisfy({ $0.isNumber }) else { return nil }
    guard MrzKey.checkDigit(doc) == docCheck else { return nil }
    guard MrzKey.checkDigit(dob) == dobCheck else { return nil }
    guard MrzKey.checkDigit(exp) == expCheck else { return nil }

    return MrzKeys(
      documentNumber: doc.replacingOccurrences(of: "<", with: ""),
      dateOfBirth: dob,
      dateOfExpiry: exp
    )
  }
}