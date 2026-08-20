package expo.modules.nfciraqireader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.nfciraqireader.nfc.*

class ScanOptions : Record {
    @Field var documentNumber: String = ""
    @Field var dateOfBirth: String = ""
    @Field var dateOfExpiry: String = ""
}

class ExpoNfcIraqiReaderModule : Module(), NfcAdapter.ReaderCallback {

    companion object {
        private const val MRZ_REQUEST_CODE = 8801
    }

    private var pendingPromise: Promise? = null
    private var options: ScanOptions? = null
    private var adapter: NfcAdapter? = null
    private var mrzPromise: Promise? = null

    private val activity: Activity?
        get() = appContext.activityProvider?.currentActivity

    override fun definition() = ModuleDefinition {
        Name("ExpoNfcIraqiReader")

        Events("onScanProgress")

        // ---------- NFC ----------

        Function("isAvailable") {
            val act = activity ?: return@Function false
            val a = NfcAdapter.getDefaultAdapter(act)
            a != null && a.isEnabled
        }

        AsyncFunction("scan") { opts: ScanOptions, promise: Promise ->
            val act = activity
            if (act == null) {
                promise.reject("NO_ACTIVITY", "ما لكيت الواجهة", null)
                return@AsyncFunction
            }
            val a = NfcAdapter.getDefaultAdapter(act)
            if (a == null || !a.isEnabled) {
                promise.reject("NFC_UNAVAILABLE", "NFC مو مفعّل أو غير مدعوم", null)
                return@AsyncFunction
            }

            pendingPromise = promise
            options = opts
            adapter = a

            emitProgress("connecting", "قرّب البطاقة من ظهر الجهاز")

            val extras = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
            }
            a.enableReaderMode(
                act,
                this@ExpoNfcIraqiReaderModule,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                extras
            )
        }

        Function("cancel") {
            stopReader()
            pendingPromise?.reject("CANCELLED", "تم الإلغاء", null)
            pendingPromise = null
            options = null
        }

        // ---------- الكاميرا ----------

        Function("hasCameraPermission") {
            val act = activity ?: return@Function false
            ContextCompat.checkSelfPermission(
                act, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }

        /** يفتح الكاميرا ويقرأ الـ MRZ من ظهر البطاقة */
        AsyncFunction("scanMrz") { promise: Promise ->
            val act = activity
            if (act == null) {
                promise.reject("NO_ACTIVITY", "ما لكيت الواجهة", null)
                return@AsyncFunction
            }

            val granted = ContextCompat.checkSelfPermission(
                act, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                promise.reject("CAMERA_DENIED", "صلاحية الكاميرا مرفوضة", null)
                return@AsyncFunction
            }

            mrzPromise = promise
            val intent = Intent(act, MrzScannerActivity::class.java)
            act.startActivityForResult(intent, MRZ_REQUEST_CODE)
        }

        OnActivityResult { _, payload ->
            if (payload.requestCode != MRZ_REQUEST_CODE) return@OnActivityResult
            val promise = mrzPromise ?: return@OnActivityResult
            mrzPromise = null

            val data = payload.data
            if (payload.resultCode != Activity.RESULT_OK || data == null) {
                promise.reject("MRZ_CANCELLED", "تم إلغاء المسح", null)
                return@OnActivityResult
            }

            promise.resolve(
                mapOf(
                    "documentNumber" to data.getStringExtra(MrzScannerActivity.EXTRA_DOC),
                    "dateOfBirth" to data.getStringExtra(MrzScannerActivity.EXTRA_DOB),
                    "dateOfExpiry" to data.getStringExtra(MrzScannerActivity.EXTRA_EXP)
                )
            )
        }

        OnDestroy { stopReader() }
    }

    private fun stopReader() {
        activity?.let { act -> adapter?.disableReaderMode(act) }
    }

    private fun emitProgress(stage: String, message: String) {
        sendEvent("onScanProgress", mapOf("stage" to stage, "message" to message))
    }

    override fun onTagDiscovered(tag: Tag) {
        val promise = pendingPromise ?: return
        val opts = options ?: return
        val iso = IsoDep.get(tag)

        if (iso == null) {
            promise.reject("BAD_TAG", "هذي البطاقة مو ISO-DEP", null)
            finish()
            return
        }

        try {
            iso.timeout = 30000
            iso.connect()

            val reader = CardReader(iso)

            if (!reader.selectApplet()) {
                throw CardException("ما لكيت تطبيق eMRTD على الشريحة")
            }

            emitProgress("authenticating", "جاري المصادقة")
            reader.performBac(
                opts.documentNumber.trim().uppercase(),
                opts.dateOfBirth.trim(),
                opts.dateOfExpiry.trim()
            )

            emitProgress("reading", "جاري سحب البيانات")

            var groups: List<Int> = emptyList()
            reader.readFileOrNull(CardReader.FID_EF_COM, "EF.COM")?.let {
                groups = ComParser.dataGroups(it)
            }

            var data = Dg1Parser.parse(reader.readFile(CardReader.FID_DG1))
                .copy(availableDataGroups = groups)

            emitProgress("photo", "جاري سحب الصورة")
            reader.readFileOrNull(CardReader.FID_DG2, "DG2")?.let {
                val (img, note) = Dg2Parser.extractFace(it)
                data = data.copy(faceImage = img, faceNote = note)
            }

            reader.readFileOrNull(CardReader.FID_DG11, "DG11")?.let {
                data = Dg11Parser.merge(it, data)
            }
            reader.readFileOrNull(CardReader.FID_DG12, "DG12")?.let {
                data = Dg12Parser.merge(it, data)
            }

            promise.resolve(toMap(data))
        } catch (e: Exception) {
            promise.reject("SCAN_FAILED", e.message ?: "فشلت القراءة", e)
        } finally {
            try { iso.close() } catch (_: Exception) {}
            finish()
        }
    }

    private fun finish() {
        stopReader()
        pendingPromise = null
        options = null
    }

    private fun toMap(d: IdData): Map<String, Any?> = mapOf(
        "documentNumber" to d.documentNumber,
        "nationalNumber" to d.nationalNumber,
        "surname" to d.surname,
        "givenNames" to d.givenNames,
        "nationality" to d.nationality,
        "dateOfBirth" to d.dateOfBirth,
        "sex" to d.sex,
        "dateOfExpiry" to d.dateOfExpiry,
        "rawMrz" to d.rawMrz,

        "fullNameArabic" to d.fullNameArabic,
        "surnameArabic" to d.surnameArabic,
        "motherName" to d.motherName,
        "grandfatherName" to d.grandfatherName,
        "placeOfBirth" to d.placeOfBirth,
        "personalNumber" to d.personalNumber,

        "issuingAuthority" to d.issuingAuthority,
        "dateOfIssue" to d.dateOfIssue,

        "faceImageBase64" to d.faceImage?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        },
        "faceFormat" to d.faceNote,

        "availableDataGroups" to d.availableDataGroups
    )
}