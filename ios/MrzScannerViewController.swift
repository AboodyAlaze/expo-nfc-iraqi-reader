import AVFoundation
import UIKit
import Vision

/// شاشة تفتح الكاميرا وتقرأ الـ MRZ من ظهر البطاقة
final class MrzScannerViewController: UIViewController {

  private let session = AVCaptureSession()
  private let queue = DispatchQueue(label: "mrz.scanner.queue")
  private var previewLayer: AVCaptureVideoPreviewLayer?
  private var finished = false

  private let onResult: (MrzKeys?) -> Void
  private let hintText: String

  init(hint: String, onResult: @escaping (MrzKeys?) -> Void) {
    self.hintText = hint
    self.onResult = onResult
    super.init(nibName: nil, bundle: nil)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) not supported") }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    setupCamera()
    setupOverlay()
  }

  override func viewWillAppear(_ animated: Bool) {
    super.viewWillAppear(animated)
    if !session.isRunning {
      queue.async { [weak self] in self?.session.startRunning() }
    }
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    if session.isRunning {
      queue.async { [weak self] in self?.session.stopRunning() }
    }
  }

  override func viewDidLayoutSubviews() {
    super.viewDidLayoutSubviews()
    previewLayer?.frame = view.bounds
  }

  // MARK: الإعداد

  private func setupCamera() {
    session.sessionPreset = .hd1920x1080

    guard let device = AVCaptureDevice.default(for: .video),
          let input = try? AVCaptureDeviceInput(device: device),
          session.canAddInput(input) else {
      finish(nil)
      return
    }
    session.addInput(input)

    let output = AVCaptureVideoDataOutput()
    output.alwaysDiscardsLateVideoFrames = true
    output.setSampleBufferDelegate(self, queue: queue)
    guard session.canAddOutput(output) else {
      finish(nil)
      return
    }
    session.addOutput(output)

    let layer = AVCaptureVideoPreviewLayer(session: session)
    layer.videoGravity = .resizeAspectFill
    layer.frame = view.bounds
    view.layer.addSublayer(layer)
    previewLayer = layer
  }

  private func setupOverlay() {
    let hint = UILabel()
    hint.text = hintText
    hint.textColor = .white
    hint.backgroundColor = UIColor.black.withAlphaComponent(0.6)
    hint.textAlignment = .center
    hint.numberOfLines = 0
    hint.font = .systemFont(ofSize: 16)
    hint.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(hint)

    let cancel = UIButton(type: .system)
    cancel.setTitle("إلغاء", for: .normal)
    cancel.setTitleColor(.white, for: .normal)
    cancel.backgroundColor = UIColor.black.withAlphaComponent(0.6)
    cancel.layer.cornerRadius = 8
    cancel.translatesAutoresizingMaskIntoConstraints = false
    cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
    view.addSubview(cancel)

    NSLayoutConstraint.activate([
      hint.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      hint.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      hint.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
      hint.heightAnchor.constraint(greaterThanOrEqualToConstant: 60),

      cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
      cancel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
      cancel.widthAnchor.constraint(equalToConstant: 90),
      cancel.heightAnchor.constraint(equalToConstant: 40),
    ])
  }

  @objc private func cancelTapped() {
    finish(nil)
  }

  private func finish(_ keys: MrzKeys?) {
    guard !finished else { return }
    finished = true
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.dismiss(animated: true) {
        self.onResult(keys)
      }
    }
  }
}

// MARK: - تحليل الإطارات

extension MrzScannerViewController: AVCaptureVideoDataOutputSampleBufferDelegate {

  func captureOutput(_ output: AVCaptureOutput,
                     didOutput sampleBuffer: CMSampleBuffer,
                     from connection: AVCaptureConnection) {
    guard !finished,
          let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

    let request = VNRecognizeTextRequest { [weak self] req, _ in
      guard let self = self, !self.finished else { return }
      guard let observations = req.results as? [VNRecognizedTextObservation] else { return }

      let text = observations
        .compactMap { $0.topCandidates(1).first?.string }
        .joined(separator: "\n")

      if let keys = MrzParser.extract(text) {
        self.finish(keys)
      }
    }

    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = false   // مهم: الـ MRZ مو لغة طبيعية
    request.recognitionLanguages = ["en-US"]

    let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .right, options: [:])
    try? handler.perform([request])
  }
}