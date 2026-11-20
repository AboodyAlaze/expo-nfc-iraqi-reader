package expo.modules.nfciraqireader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.util.Size
import expo.modules.nfciraqireader.nfc.MrzParser
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * شاشة تفتح الكاميرا وتقرأ الـ MRZ من ظهر البطاقة.
 * نفس الإطار اللي انقرأ منه الـ MRZ ينحفظ كصورة JPEG ويرجع مساره (EXTRA_IMAGE)،
 * فلقطة وحدة تعطي القراءة وصورة الخلف.
 */
class MrzScannerActivity : androidx.activity.ComponentActivity() {

    companion object {
        const val EXTRA_DOC = "documentNumber"
        const val EXTRA_DOB = "dateOfBirth"
        const val EXTRA_EXP = "dateOfExpiry"
        const val EXTRA_HINT = "hint"
        const val EXTRA_IMAGE = "imageUri"
        const val EXTRA_ERROR = "error"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var done = false
    private lateinit var previewView: PreviewView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setResult(Activity.RESULT_CANCELED, Intent().apply {
                putExtra(EXTRA_ERROR, "CAMERA_DENIED")
            })
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

        val hint = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_HINT)
                ?: "ضع ظهر البطاقة كامل داخل الإطار وثبّت الكاميرا"
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

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            // دقة أعلى من الافتراضي (640x480) حتى تطلع صورة الخلف واضحة
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
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
                // الـ listeners تشتغل على نفس الـ executor (خيط واحد) حتى يبقى proxy مفتوح
                // لحد ما ننتهي من حفظ الإطار، وحتى ما نثقّل الـ main thread
                recognizer.process(image)
                    .addOnSuccessListener(executor) { result ->
                        val keys = MrzParser.extract(result.text)
                        if (keys != null && !done) {
                            done = true
                            val imageUri = saveFrame(proxy)
                            finishWith(
                                keys.documentNumber,
                                keys.dateOfBirth,
                                keys.dateOfExpiry,
                                imageUri
                            )
                        }
                    }
                    .addOnCompleteListener(executor) { proxy.close() }
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

    /** يحفظ الإطار الحالي كـ JPEG بالـ cache ويرجع مساره (file://...) أو null إذا فشل */
    private fun saveFrame(proxy: ImageProxy): String? {
        return try {
            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            val bitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { if (it !== raw) raw.recycle() }
            } else {
                raw
            }

            val file = File(cacheDir, "id_back_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun finishWith(doc: String, dob: String, exp: String, imageUri: String?) {
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_DOC, doc)
                putExtra(EXTRA_DOB, dob)
                putExtra(EXTRA_EXP, exp)
                putExtra(EXTRA_IMAGE, imageUri)
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