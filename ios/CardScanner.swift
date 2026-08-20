import Foundation
@preconcurrency import CoreNFC

/// يدير جلسة CoreNFC وينفّذ BAC ويقرأ الملفات
final class CardScanner: NSObject {

  private let onProgress: (String, String) -> Void
  private let onFinish: (Result<[String: Any], Error>) -> Void

  private var session: NFCTagReaderSession?
  private var sm: SecureMessaging?

  private var docNumber = ""
  private var dob = ""
  private var expiry = ""
  private var finished = false

  static let aidEMRTD = Data([0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01])

  enum FID {
    static let efCom: UInt16 = 0x011E
    static let dg1: UInt16   = 0x0101
    static let dg2: UInt16   = 0x0102
    static let dg11: UInt16  = 0x010B
    static let dg12: UInt16  = 0x010C
  }

  init(onProgress: @escaping (String, String) -> Void,
       onFinish: @escaping (Result<[String: Any], Error>) -> Void) {
    self.onProgress = onProgress
    self.onFinish = onFinish
  }

  func start(doc: String, dob: String, expiry: String) {
    self.docNumber = doc
    self.dob = dob
    self.expiry = expiry
    self.finished = false

    onProgress("connecting", "قرّب البطاقة من أعلى ظهر الجهاز")

    session = NFCTagReaderSession(pollingOption: [.iso14443], delegate: self, queue: nil)
    session?.alertMessage = "قرّب البطاقة الوطنية من أعلى الجهاز وثبّتها"
    session?.begin()
  }

  func cancel() {
    session?.invalidate()
    session = nil
  }

  private func complete(_ result: Result<[String: Any], Error>) {
    guard !finished else { return }
    finished = true
    onFinish(result)
  }
}

// MARK: - جلسة CoreNFC

extension CardScanner: NFCTagReaderSessionDelegate {

  func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

  func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
    let ns = error as NSError
    if ns.code == NFCReaderError.readerSessionInvalidationErrorUserCanceled.rawValue {
      complete(.failure(CardError.bacFailed("تم الإلغاء")))
      return
    }
    complete(.failure(error))
  }

  func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
    guard let first = tags.first, case let .iso7816(tag) = first else {
      session.invalidate(errorMessage: "هذي البطاقة مو ISO7816")
      return
    }

    nonisolated(unsafe) let s = session
    nonisolated(unsafe) let t = tag

    s.connect(to: first) { error in
      if let error = error {
        s.invalidate(errorMessage: "فشل الاتصال: \(error.localizedDescription)")
        return
      }
      Task { await self.readCard(tag: t, session: s) }
    }
  }
}

// MARK: - منطق القراءة

extension CardScanner {

  private func send(_ tag: NFCISO7816Tag, _ apdu: NFCISO7816APDU) async throws -> (Data, UInt8, UInt8) {
    try await withCheckedThrowingContinuation { cont in
      tag.sendCommand(apdu: apdu) { data, sw1, sw2, error in
        if let error = error { cont.resume(throwing: error) }
        else { cont.resume(returning: (data, sw1, sw2)) }
      }
    }
  }

  private func readCard(tag: NFCISO7816Tag, session: NFCTagReaderSession) async {
    do {
      // SELECT الأبلت
      let selectAid = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0xA4,
                                     p1Parameter: 0x04, p2Parameter: 0x0C,
                                     data: Self.aidEMRTD, expectedResponseLength: -1)
      let (_, s1, s2) = try await send(tag, selectAid)
      guard s1 == 0x90, s2 == 0x00 else { throw CardError.noApplet }

      // BAC
      onProgress("authenticating", "جاري المصادقة")
      session.alertMessage = "جاري المصادقة..."
      try await performBAC(tag: tag)

      // EF.COM
      onProgress("reading", "جاري سحب البيانات")
      session.alertMessage = "جاري سحب البيانات..."
      var groups: [Int] = []
      if let com = try? await readFile(tag: tag, fid: FID.efCom) {
        groups = ComParser.dataGroups(com)
      }

      // DG1
      let dg1 = try await readFile(tag: tag, fid: FID.dg1)
      var data = Dg1Parser.parse(dg1)
      data.availableDataGroups = groups

      // DG2 — الصورة
      onProgress("photo", "جاري سحب الصورة")
      session.alertMessage = "جاري سحب الصورة..."
      if let dg2 = try? await readFile(tag: tag, fid: FID.dg2) {
        let (img, note) = Dg2Parser.extractFace(dg2)
        data.faceImage = img
        data.faceNote = note
      }

      // DG11 / DG12
      if let dg11 = try? await readFile(tag: tag, fid: FID.dg11) {
        data = Dg11Parser.merge(dg11, into: data)
      }
      if let dg12 = try? await readFile(tag: tag, fid: FID.dg12) {
        data = Dg12Parser.merge(dg12, into: data)
      }

      session.alertMessage = "تمت القراءة بنجاح"
      session.invalidate()
      complete(.success(Self.toDictionary(data)))

    } catch {
      session.invalidate(errorMessage: error.localizedDescription)
      complete(.failure(error))
    }
  }

  // MARK: BAC

  private func performBAC(tag: NFCISO7816Tag) async throws {
    let (kEnc, kMac) = MrzKey.deriveBacKeys(doc: docNumber, dob: dob, expiry: expiry)

    let getChallenge = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x84,
                                      p1Parameter: 0x00, p2Parameter: 0x00,
                                      data: Data(), expectedResponseLength: 8)
    let (rndIcc, c1, c2) = try await send(tag, getChallenge)
    guard c1 == 0x90, c2 == 0x00, rndIcc.count == 8 else {
      throw CardError.bacFailed("GET CHALLENGE فشل")
    }

    let rndIfd = Crypto.randomBytes(8)
    let kIfd = Crypto.randomBytes(16)
    let s = rndIfd + rndIcc + kIfd

    let eIfd = try Crypto.tdesEncrypt(key16: kEnc, data: s)
    let mIfd = try Crypto.retailMac(key16: kMac, paddedData: Crypto.pad(eIfd))

    let extAuth = NFCISO7816APDU(instructionClass: 0x00, instructionCode: 0x82,
                                 p1Parameter: 0x00, p2Parameter: 0x00,
                                 data: eIfd + mIfd, expectedResponseLength: 40)
    let (resp, e1, e2) = try await send(tag, extAuth)
    guard e1 == 0x90, e2 == 0x00, resp.count == 40 else {
      throw CardError.bacFailed(String(format: "تأكد من رقم الوثيقة والتواريخ (%02X%02X)", e1, e2))
    }

    let eIcc = Data(resp.prefix(32))
    let mIcc = Data(resp.suffix(8))
    guard try Crypto.retailMac(key16: kMac, paddedData: Crypto.pad(eIcc)) == mIcc else {
      throw CardError.macMismatch
    }

    let dec = [UInt8](try Crypto.tdesDecrypt(key16: kEnc, data: eIcc))
    let rndIccBack = Data(dec[0..<8])
    let rndIfdBack = Data(dec[8..<16])
    let kIcc = Data(dec[16..<32])

    guard rndIfdBack == rndIfd, rndIccBack == rndIcc else {
      throw CardError.bacFailed("الأرقام العشوائية ما تطابقت")
    }

    let seed = Crypto.xor(kIfd, kIcc)
    let ksEnc = MrzKey.kdf(seed: seed, counter: 1)
    let ksMac = MrzKey.kdf(seed: seed, counter: 2)

    var ssc: UInt64 = 0
    let iccB = [UInt8](rndIcc)
    let ifdB = [UInt8](rndIfd)
    for i in 4..<8 { ssc = (ssc << 8) | UInt64(iccB[i]) }
    for i in 4..<8 { ssc = (ssc << 8) | UInt64(ifdB[i]) }

    sm = SecureMessaging(ksEnc: ksEnc, ksMac: ksMac, ssc: ssc)
  }

  // MARK: قراءة الملفات

  private func sendSecure(_ tag: NFCISO7816Tag, cla: UInt8, ins: UInt8,
                          p1: UInt8, p2: UInt8, data: Data?, le: Int?) async throws -> Data {
    guard let sm = sm else { throw CardError.bacFailed("لازم BAC أولاً") }
    let (header, body) = try sm.wrap(cla: cla, ins: ins, p1: p1, p2: p2, data: data, le: le)

    let apdu = NFCISO7816APDU(instructionClass: header[0],
                              instructionCode: header[1],
                              p1Parameter: header[2],
                              p2Parameter: header[3],
                              data: body,
                              expectedResponseLength: 256)
    let (resp, s1, s2) = try await send(tag, apdu)
    return try sm.unwrap(resp, sw1: s1, sw2: s2)
  }

  private func readFile(tag: NFCISO7816Tag, fid: UInt16) async throws -> Data {
    let fidData = Data([UInt8(fid >> 8), UInt8(fid & 0xFF)])
    _ = try await sendSecure(tag, cla: 0x00, ins: 0xA4, p1: 0x02, p2: 0x0C,
                             data: fidData, le: nil)

    let head = try await sendSecure(tag, cla: 0x00, ins: 0xB0, p1: 0x00, p2: 0x00,
                                    data: nil, le: 4)
    let total = Tlv.totalLength(head)

    var out = head
    var offset = head.count
    while offset < total {
      let chunk = min(0x80, total - offset)
      let part = try await sendSecure(tag, cla: 0x00, ins: 0xB0,
                                      p1: UInt8(offset >> 8),
                                      p2: UInt8(offset & 0xFF),
                                      data: nil, le: chunk)
      if part.isEmpty { break }
      out += part
      offset += part.count
    }
    return out
  }

  // MARK: تحويل للقاموس

  static func toDictionary(_ d: IdData) -> [String: Any] {
    var out: [String: Any] = [
      "documentNumber": d.documentNumber,
      "nationalNumber": d.nationalNumber,
      "surname": d.surname,
      "givenNames": d.givenNames,
      "nationality": d.nationality,
      "sex": d.sex,
      "rawMrz": d.rawMrz,

      // التواريخ منسّقة DD/MM/YYYY مع النسخة الخام YYMMDD
      "dateOfBirth": TextCodec.formatDate(d.dateOfBirth),
      "dateOfBirthRaw": d.dateOfBirth,
      "dateOfExpiry": TextCodec.formatDate(d.dateOfExpiry),
      "dateOfExpiryRaw": d.dateOfExpiry,
      "dateOfIssue": TextCodec.formatDate(d.dateOfIssue),
      "dateOfIssueRaw": d.dateOfIssue,

      "fullNameArabic": d.fullNameArabic,
      "surnameArabic": d.surnameArabic,
      "motherName": d.motherName,
      "grandfatherName": d.grandfatherName,
      "placeOfBirth": d.placeOfBirth,
      "personalNumber": d.personalNumber,

      "issuingAuthority": d.issuingAuthority,

      "faceFormat": d.faceNote,
      "availableDataGroups": d.availableDataGroups,
    ]
    if let img = d.faceImage {
      out["faceImageBase64"] = img.base64EncodedString()
    }
    return out
  }
}