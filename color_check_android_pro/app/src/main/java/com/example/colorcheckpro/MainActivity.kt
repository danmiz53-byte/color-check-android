package com.example.colorcheckpro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.colorcheckpro.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.max
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private var currentBitmap: Bitmap? = null
    private val calibration = ColorPipeline.Calibration()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bmp = loadBitmapFromUri(uri)
            setBitmap(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toggleAdvanced.setOnCheckedChangeListener { _, isChecked ->
            b.advRow.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                b.toggleCalibrate.isChecked = false
                b.toggleLasso.isChecked = false
                b.overlay.visibility = View.GONE
            }
        }

        b.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        b.btnCamera.setOnClickListener { capturePhoto() }
        b.btnReset.setOnClickListener {
            calibration.clear()
            b.toggleCalibrate.isChecked = false
            b.toggleLasso.isChecked = false
            b.overlay.clear()
            b.overlay.visibility = View.GONE
            toast("Reset")
        }

        b.toggleCalibrate.setOnCheckedChangeListener { _, isOn ->
            if (isOn) {
                b.toggleLasso.isChecked = false
                b.overlay.visibility = View.GONE
                b.txtHint.text = "Calibration: tap then choose White/Gray/Black"
            } else {
                b.txtHint.text = "Tap to measure. Advanced: Calibrate/Lasso."
            }
        }

        b.toggleLasso.setOnCheckedChangeListener { _, isOn ->
            if (isOn) {
                b.toggleCalibrate.isChecked = false
                b.overlay.visibility = View.VISIBLE
                b.overlay.clear()
                b.txtHint.text = "Free region: draw with finger, then tap Lasso again to finish"
            } else {
                b.overlay.visibility = View.GONE
                b.txtHint.text = "Tap to measure. Advanced: Calibrate/Lasso."
            }
        }

        // Finish lasso when toggled off after drawing
        b.toggleLasso.setOnClickListener {
            if (!b.toggleLasso.isChecked) {
                val bmp = currentBitmap ?: return@setOnClickListener
                val ptsView = b.overlay.getPoints()
                if (ptsView.size >= 3) {
                    val ptsBmp = ptsView.mapNotNull { viewPointToBitmapPoint(it) }
                    val res = ColorPipeline.sampleInMask(bmp, ptsBmp, calibration)
                    if (res != null) showResult(res) else toast("Lasso area too small")
                }
            }
        }

        // Tap to measure on imageView
        b.imageView.setOnTouchListener { _, ev ->
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
            val bmp = currentBitmap ?: return@setOnTouchListener true
            val pt = viewToBitmapPoint(ev.x, ev.y) ?: return@setOnTouchListener true

            if (b.toggleCalibrate.isChecked) {
                openCalDialog(pt.x, pt.y)
                return@setOnTouchListener true
            }

            val res = ColorPipeline.sampleAt(bmp, pt.x, pt.y, win = 23, cal = calibration)
            showResult(res)
            true
        }

        // Keep overlay updated (optional)
        b.overlay.listener = object : LassoOverlayView.Listener {
            override fun onLassoUpdated(points: List<PointF>) {
                // no-op (future: show live stats)
            }
        }

        // Camera permission & start
        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            toast("Camera permission denied")
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(b.previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(this, selector, preview, imageCapture)
            // show camera, hide still image until user captures/loads
            b.previewView.visibility = View.VISIBLE
            b.imageView.visibility = View.GONE
            b.overlay.visibility = View.GONE
        } catch (e: Exception) {
            toast("Camera bind failed")
        }
    }

    private fun capturePhoto() {
        val cap = imageCapture ?: return
        cap.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = imageProxyToBitmap(image)
                    image.close()
                    setBitmap(bmp)
                }
                override fun onError(exception: ImageCaptureException) {
                    toast("Capture failed")
                }
            }
        )
    }

    private fun setBitmap(bmp: Bitmap) {
        // Downscale for stability
        val maxDim = 2400
        val scaled = scaleDown(bmp, maxDim)
        currentBitmap = scaled
        b.imageView.setImageBitmap(scaled)
        b.previewView.visibility = View.GONE
        b.imageView.visibility = View.VISIBLE
        // Reset overlay path to avoid mismatch
        b.overlay.clear()
        if (b.toggleLasso.isChecked) b.overlay.visibility = View.VISIBLE
    }

    private fun openCalDialog(x: Float, y: Float) {
        val bmp = currentBitmap ?: return
        val base = ColorPipeline.sampleAt(bmp, x, y, win = 19, cal = null)
        val items = arrayOf("White", "Gray", "Black")
        AlertDialog.Builder(this)
            .setTitle("This patch is…")
            .setItems(items) { _, which ->
                val kind = when (which) {
                    0 -> ColorPipeline.CalPoint.Kind.WHITE
                    1 -> ColorPipeline.CalPoint.Kind.GRAY
                    else -> ColorPipeline.CalPoint.Kind.BLACK
                }
                calibration.add(ColorPipeline.CalPoint(kind, base.linRGB))
                val msg = if (calibration.isReady()) "Calibration ready (${calibrationHash()})" else "Add one more patch"
                toast(msg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun calibrationHash(): String = "${System.identityHashCode(calibration)}"

    private fun showResult(res: ColorPipeline.SampleResult) {
        b.swatch.setBackgroundColor(Color.rgb(res.sRGB[0], res.sRGB[1], res.sRGB[2]))
        val lab = res.labD50
        val qPct = (res.quality * 100).roundToInt()
        b.txtResult.text = "${res.hex}  RGB(${res.sRGB[0]},${res.sRGB[1]},${res.sRGB[2]})\nLab D50 (${lab[0].round1()}, ${lab[1].round1()}, ${lab[2].round1()})  •  Q ${qPct}%  •  ${res.note}"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ----- coordinate mapping -----

    private fun viewToBitmapPoint(vx: Float, vy: Float): PointF? {
        val bmp = currentBitmap ?: return null
        val iv = b.imageView
        val matrix = Matrix(iv.imageMatrix)
        val inv = Matrix()
        if (!matrix.invert(inv)) return null
        val pts = floatArrayOf(vx, vy)
        inv.mapPoints(pts)
        val x = pts[0]
        val y = pts[1]
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return null
        return PointF(x, y)
    }

    private fun viewPointToBitmapPoint(p: PointF): PointF? = viewToBitmapPoint(p.x, p.y)

    // ----- bitmap helpers -----

    private fun loadBitmapFromUri(uri: android.net.Uri): Bitmap {
        val src = contentResolver.openInputStream(uri) ?: throw IllegalStateException("No stream")
        src.use { return BitmapFactory.decodeStream(it) ?: throw IllegalStateException("Decode failed") }
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val m = max(w, h)
        if (m <= maxDim) return bmp
        val s = maxDim.toFloat() / m.toFloat()
        val nw = (w * s).roundToInt().coerceAtLeast(1)
        val nh = (h * s).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

private fun Double.round1(): String = String.format("%.1f", this)
