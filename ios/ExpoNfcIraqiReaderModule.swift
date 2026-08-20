import ExpoModulesCore
@preconcurrency import CoreNFC
import AVFoundation
import UIKit

public class ExpoNfcIraqiReaderModule: Module {

  private var reader: CardScanner?

  public func definition() -> ModuleDefinition {
    Name("ExpoNfcIraqiReader")

    Events("onScanProgress")

    // ---------- NFC ----------

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

    // ---------- الكاميرا ----------

    Function("hasCameraPermission") { () -> Bool in
      return AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    }

    /// يفتح الكاميرا ويقرأ الـ MRZ من ظهر البطاقة
    AsyncFunction("scanMrz") { (promise: Promise) in
      guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
        promise.reject("CAMERA_DENIED", "صلاحية الكاميرا مرفوضة")
        return
      }

      DispatchQueue.main.async {
        guard let presenter = self.appContext?.utilities?.currentViewController() else {
          promise.reject("NO_ACTIVITY", "ما لكيت الواجهة")
          return
        }

        let vc = MrzScannerViewController(
          hint: "وجّه الكاميرا على الأسطر السفلية بظهر البطاقة"
        ) { keys in
          guard let keys = keys else {
            promise.reject("MRZ_CANCELLED", "تم إلغاء المسح")
            return
          }
          promise.resolve([
            "documentNumber": keys.documentNumber,
            "dateOfBirth": keys.dateOfBirth,
            "dateOfExpiry": keys.dateOfExpiry,
          ])
        }

        vc.modalPresentationStyle = .fullScreen
        presenter.present(vc, animated: true)
      }
    }
  }
}

struct ScanOptions: Record {
  @Field var documentNumber: String = ""
  @Field var dateOfBirth: String = ""
  @Field var dateOfExpiry: String = ""
}