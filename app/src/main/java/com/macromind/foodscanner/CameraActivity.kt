package com.macromind.foodscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraActivity — premium dark camera screen with shutter animation.
 */
class CameraActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var previewView:   PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var statusText:    TextView
    private lateinit var progressBar:   ProgressBar

    // ── Camera ─────────────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val PERMISSION_REQUEST_CODE = 100

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // Edge-to-edge immersive layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        ScanSession.clear()
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        }

        setListeners()
        playEntranceAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ── Entrance animation ─────────────────────────────────────────────────
    private fun playEntranceAnimation() {
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val topOverlay = findViewById<View>(R.id.topOverlay)

        // Bottom bar slides up
        bottomBar.translationY = 120f
        bottomBar.alpha = 0f
        bottomBar.animate()
            .translationY(0f).alpha(1f)
            .setDuration(300).setStartDelay(100)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Top overlay fades in
        topOverlay.alpha = 0f
        topOverlay.animate()
            .alpha(1f)
            .setDuration(250).setStartDelay(50)
            .start()

        // Capture button bounces in
        captureButton.scaleX = 0f
        captureButton.scaleY = 0f
        captureButton.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(300).setStartDelay(200)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()
    }

    // ── Bind views ─────────────────────────────────────────────────────────
    private fun bindViews() {
        previewView   = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        statusText    = findViewById(R.id.statusText)
        progressBar   = findViewById(R.id.progressBar)
    }

    // ── Permissions ────────────────────────────────────────────────────────
    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera & storage permissions are required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ── Camera setup ───────────────────────────────────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                setStatus("Point camera at a food label")
            } catch (e: Exception) {
                Log.e("MacroMind", "Camera start failed: ${e.message}")
                setStatus("Camera error: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ── Listeners ──────────────────────────────────────────────────────────
    private fun setListeners() {
        captureButton.setOnClickListener {
            // Shutter press animation
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
            takePhoto()
        }
        galleryButton.setOnClickListener {
            // Small press animation
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(60).start()
                }.start()
            pickFromGallery()
        }
    }

    // ── Capture ────────────────────────────────────────────────────────────
    private fun takePhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true, "Capturing…")

        // Flash overlay for shutter effect
        val flash = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        (window.decorView as android.view.ViewGroup).addView(flash)
        flash.animate().alpha(0.6f).setDuration(60).withEndAction {
            flash.animate().alpha(0f).setDuration(150).withEndAction {
                (window.decorView as android.view.ViewGroup).removeView(flash)
            }.start()
        }.start()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        if (bitmap != null) {
                            launchCropActivity(bitmap)
                        } else {
                            showError("Could not process the photo. Please try again.")
                        }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MacroMind", "Capture failed: ${exception.message}")
                    showError("Capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes  = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("MacroMind", "imageProxyToBitmap failed: ${e.message}")
            null
        }
    }

    // ── Gallery ────────────────────────────────────────────────────────────
    private fun pickFromGallery() {
        setStatus("Opening gallery…")
        ImagePickerHelper.openGallery(this)
    }

    @Deprecated("Using legacy onActivityResult for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == ImagePickerHelper.GALLERY_REQUEST_CODE
            && resultCode == RESULT_OK) {

            val uri: Uri? = data?.data
            if (uri == null) {
                showError("No image selected.")
                return
            }

            setLoading(true, "Loading image…")

            Thread {
                val bitmap = ImagePickerHelper.loadBitmapFromUri(this, uri)
                if (bitmap != null) {
                    launchCropActivity(bitmap)
                } else {
                    showError("Could not load the image. Try a different photo.")
                }
            }.start()
        }
    }

    // ── Launch CropActivity ────────────────────────────────────────────────
    private fun launchCropActivity(bitmap: Bitmap) {
        CropActivity.pendingBitmap = bitmap
        runOnUiThread {
            setLoading(false)
            startActivity(Intent(this, CropActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out)
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────
    private fun setLoading(loading: Boolean, message: String = "") {
        runOnUiThread {
            progressBar.visibility  = if (loading) View.VISIBLE else View.GONE
            captureButton.isEnabled = !loading
            galleryButton.isEnabled = !loading
            if (message.isNotEmpty()) statusText.text = message
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun showError(message: String) {
        runOnUiThread {
            setLoading(false)
            statusText.text = "❌ $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
