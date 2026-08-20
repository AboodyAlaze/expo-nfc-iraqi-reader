package expo.modules.nfciraqireader

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import expo.modules.nfciraqireader.nfc.MrzParser
import java.util.concurrent.Executors

/** شاشة تفتح الكاميرا وتقرأ الـ MRZ من ظهر البطاقة */
class MrzScannerActivity : androidx.activity.ComponentActivity() {

    companion object {
        const val EXTRA_DOC = "documentNumber"
        const val EXTRA_DOB = "dateOfBirth"
        const val EXTRA_EXP = "dateOfExpiry"
        const val EXTRA_HINT = "hint"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

        val hint = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_HINT)
                ?: "وجّه الكاميرا على الأسطر السفلية بظهر البطاقة"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        root.addView(hint)

        setContentView(root)
        startCamera(previewView)
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                @Suppress("UnsafeOptInUsageError")
                val media = proxy.image
                if (media == null || done) {
                    proxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val keys = MrzParser.extract(result.text)
                        if (keys != null && !done) {
                            done = true
                            finishWith(keys.documentNumber, keys.dateOfBirth, keys.dateOfExpiry)
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this as LifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWith(doc: String, dob: String, exp: String) {
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_DOC, doc)
                putExtra(EXTRA_DOB, dob)
                putExtra(EXTRA_EXP, exp)
            })
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }
}