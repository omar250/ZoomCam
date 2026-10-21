package com.example.zoomcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var captureBtn: Button
    private lateinit var resultPanel: LinearLayout
    private lateinit var beforeImg: ImageView
    private lateinit var afterImg: ImageView
    private lateinit var saveBtn: Button
    private lateinit var closeBtn: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var sr: SuperResolution
    private val btnText = "التقاط + تحسين"
    private var lastEnhanced: Bitmap? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "الكاميرا مطلوبة", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        zoomSeek = findViewById(R.id.zoomSeek)
        zoomLabel = findViewById(R.id.zoomLabel)
        captureBtn = findViewById(R.id.captureBtn)
        resultPanel = findViewById(R.id.resultPanel)
        beforeImg = findViewById(R.id.beforeImg)
        afterImg = findViewById(R.id.afterImg)
        saveBtn = findViewById(R.id.saveBtn)
        closeBtn = findViewById(R.id.closeBtn)

        sr = SuperResolution(this)
        if (!sr.hasModel) {
            Toast.makeText(this, "النموذج غير موجود: سيتم التكبير العادي", Toast.LENGTH_LONG).show()
        }

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val state = camera?.cameraInfo?.zoomState?.value ?: return
                val ratio = state.minZoomRatio + (state.maxZoomRatio - state.minZoomRatio) * p / 100f
                camera?.cameraControl?.setZoomRatio(ratio)
                zoomLabel.text = String.format(Locale.US, "×%.1f", ratio)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        captureBtn.setOnClickListener { capture() }
        closeBtn.setOnClickListener { resultPanel.visibility = View.GONE }
        saveBtn.setOnClickListener {
            val bmp = lastEnhanced
            if (bmp == null) {
                Toast.makeText(this, "لا توجد صورة للحفظ", Toast.LENGTH_SHORT).show()
            } else if (saveToGallery(bmp)) {
                val msg = if (Build.VERSION.SDK_INT >= 29)
                    "تم الحفظ في المعرض (ألبوم ZoomCam)"
                else "تم الحفظ في مجلد التطبيق"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "فشل الحفظ", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun saveToGallery(bmp: Bitmap): Boolean {
        return try {
            val fileName = "ZoomCam_" + System.currentTimeMillis() + ".jpg"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ZoomCam")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
                FileOutputStream(File(dir, fileName)).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        captureBtn.isEnabled = false
        captureBtn.text = "جارٍ الالتقاط..."
        ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                var bmp = image.toBitmap()
                image.close()
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val basicImg = sr.basic(bmp)
                val enhanced = sr.enhanceFull(bmp) { done, total ->
                    runOnUiThread { captureBtn.text = "جارٍ المعالجة $done/$total" }
                }
                lastEnhanced = enhanced
                runOnUiThread {
                    beforeImg.setImageBitmap(basicImg)
                    afterImg.setImageBitmap(enhanced)
                    resultPanel.visibility = View.VISIBLE
                    captureBtn.text = btnText
                    captureBtn.isEnabled = true
                }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "فشل الالتقاط", Toast.LENGTH_SHORT).show()
                    captureBtn.text = btnText
                    captureBtn.isEnabled = true
                }
            }
        })
    }
}
