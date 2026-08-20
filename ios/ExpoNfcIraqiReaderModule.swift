import ExpoModulesCore
@preconcurrency import CoreNFC

public class ExpoNfcIraqiReaderModule: Module {

  private var reader: CardScanner?

  public func definition() -> ModuleDefinition {
    Name("ExpoNfcIraqiReader")

    Events("onScanProgress")

    Function("isAvailable") { () -> Bool in
      return NFCTagReaderSession.readingAvailable
    }

    AsyncFunction("scan") { (options: ScanOptions, promise: Promise) in
      guard NFCTagReaderSession.readingAvailable else {
        promise.reject("NFC_UNAVAILABLE", "الجهاز ما يدعم NFC أو الصلاحية ناقصة")
        return
      }

      let scanner = CardScanner(
        onProgress: { [weak self] stage, message in
          self?.sendEvent("onScanProgress", ["stage": stage, "message": message])
        },
        onFinish: { result in
          switch result {
          case .success(let data):
            promise.resolve(data)
          case .failure(let error):
            promise.reject("SCAN_FAILED", error.localizedDescription)
          }
        }
      )
      self.reader = scanner
      scanner.start(
        doc: options.documentNumber.trimmingCharacters(in: .whitespaces).uppercased(),
        dob: options.dateOfBirth.trimmingCharacters(in: .whitespaces),
        expiry: options.dateOfExpiry.trimmingCharacters(in: .whitespaces)
      )
    }

    Function("cancel") {
      self.reader?.cancel()
      self.reader = nil
    }
  }
}

struct ScanOptions: Record {
  @Field var documentNumber: String = ""
  @Field var dateOfBirth: String = ""
  @Field var dateOfExpiry: String = ""
}