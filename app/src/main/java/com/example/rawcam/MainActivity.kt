package com.example.rawcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Range
import android.view.TextureView
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var switchRaw: SwitchMaterial
    private lateinit var exposureModeGroup: RadioGroup
    private lateinit var manualControlsPanel: View
    private lateinit var rowIso: View
    private lateinit var rowShutter: View
    private lateinit var tvIsoLabel: TextView
    private lateinit var tvShutterLabel: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var btnCapture: View
    private lateinit var lastShotThumbnail: ImageView
    private lateinit var tvStatus: TextView

    private lateinit var cameraController: CameraController
    private var capabilities: CameraCapabilities? = null

    // The shutter slider only ever needs to reach 1 full second — capping its
    // mapped range there (rather than the device's raw max, which can run to
    // several seconds on some sensors) keeps 1s cleanly reachable at the
    // slider's own maximum and gives the whole practical handheld range
    // better precision. Falls back to the device's true max if that's lower.
    private var shutterSliderRange: Range<Long>? = null
    private var deviceExposureMaxNs: Long = 0L // raw, uncapped — for the diagnostic label
    private var lastPhotoUri: Uri? = null

    // WRITE_EXTERNAL_STORAGE is only needed (and only declared, maxSdkVersion 28)
    // pre-Android 10 — scoped storage on 10+ needs no extra permission for this.
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                openCameraIfReady()
            } else {
                tvStatus.text = getString(R.string.status_permission_denied)
                Toast.makeText(this, R.string.status_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        gridOverlay = findViewById(R.id.gridOverlay)
        switchRaw = findViewById(R.id.switchRaw)
        exposureModeGroup = findViewById(R.id.exposureModeGroup)
        manualControlsPanel = findViewById(R.id.manualControlsPanel)
        rowIso = findViewById(R.id.rowIso)
        rowShutter = findViewById(R.id.rowShutter)
        tvIsoLabel = findViewById(R.id.tvIsoLabel)
        tvShutterLabel = findViewById(R.id.tvShutterLabel)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        btnCapture = findViewById(R.id.btnCapture)
        lastShotThumbnail = findViewById(R.id.lastShotThumbnail)
        tvStatus = findViewById(R.id.tvStatus)

        cameraController = CameraController(
            context = this,
            onStatus = { message -> runOnUiThread { tvStatus.text = message } },
            onCapabilities = { caps -> runOnUiThread { applyCapabilities(caps) } },
            onFocusState = { state -> runOnUiThread { gridOverlay.setFocusState(state) } },
            onLastPhoto = { bitmap, uri -> runOnUiThread { showLastPhoto(bitmap, uri) } }
        )

        switchRaw.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            cameraController.setRawEnabled(isChecked)
        }

        exposureModeGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val manual = checkedId == R.id.radioManual
            cameraController.setManualMode(manual)
            manualControlsPanel.visibility = if (manual) View.VISIBLE else View.GONE
        }

        setUpManualSliders()

        btnCapture.setOnClickListener {
            setCaptureButtonEnabled(false)
            cameraController.captureStill {
                runOnUiThread { setCaptureButtonEnabled(true) }
            }
        }

        lastShotThumbnail.setOnClickListener {
            val uri = lastPhotoUri ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*") })
            } catch (e: Exception) {
                Toast.makeText(this, R.string.status_no_viewer, Toast.LENGTH_SHORT).show()
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCameraIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraController.closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun showLastPhoto(bitmap: Bitmap, uri: Uri) {
        lastPhotoUri = uri
        lastShotThumbnail.setImageBitmap(bitmap)
        lastShotThumbnail.visibility = View.VISIBLE
    }

    /**
     * A plain View (see shutter_button.xml) doesn't get MaterialButton's
     * built-in disabled styling, so this fakes it with alpha.
     */
    private fun setCaptureButtonEnabled(enabled: Boolean) {
        btnCapture.isEnabled = enabled
        btnCapture.alpha = if (enabled) 1f else 0.5f
    }

    // -----------------------------------------------------------------------
    // Manual controls: capability-driven visibility + slider <-> value mapping
    // -----------------------------------------------------------------------

    /** Called whenever capabilities or current manual values change. */
    private fun applyCapabilities(caps: CameraCapabilities) {
        capabilities = caps

        val isoRange = caps.isoRange
        val expRange = caps.exposureTimeRangeNs
        val hasManualExposure = caps.manualSensorSupported && isoRange != null && expRange != null

        exposureModeGroup.isEnabled = hasManualExposure
        rowIso.visibility = if (hasManualExposure) View.VISIBLE else View.GONE
        rowShutter.visibility = if (hasManualExposure) View.VISIBLE else View.GONE

        if (hasManualExposure && isoRange != null && expRange != null) {
            deviceExposureMaxNs = expRange.upper

            seekIso.max = SEEK_MAX
            seekIso.progress = progressFromIso(caps.defaultIso, isoRange)
            tvIsoLabel.text = getString(R.string.label_iso_value, caps.defaultIso)

            val sliderRange = Range(expRange.lower, min(expRange.upper, ONE_SECOND_NS).coerceAtLeast(expRange.lower))
            shutterSliderRange = sliderRange
            seekShutter.max = SEEK_MAX
            seekShutter.progress = progressFromExposureNs(caps.defaultExposureTimeNs, sliderRange)
            tvShutterLabel.text = getString(
                R.string.label_shutter_value,
                formatShutterSpeed(caps.defaultExposureTimeNs),
                formatShutterSpeed(deviceExposureMaxNs)
            )
        }
    }

    private fun setUpManualSliders() {
        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = capabilities?.isoRange ?: return
                val iso = isoFromProgress(progress, range)
                tvIsoLabel.text = getString(R.string.label_iso_value, iso)
                if (fromUser) cameraController.setManualIso(iso)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val range = shutterSliderRange ?: return
                val ns = exposureNsFromProgress(progress, range)
                tvShutterLabel.text = getString(
                    R.string.label_shutter_value,
                    formatShutterSpeed(ns),
                    formatShutterSpeed(deviceExposureMaxNs)
                )
                if (fromUser) cameraController.setManualExposureTimeNs(ns)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // ISO: linear across the sensor's supported range.
    private fun progressFromIso(iso: Int, range: Range<Int>): Int {
        val span = (range.upper - range.lower).coerceAtLeast(1)
        return (((iso - range.lower).toFloat() / span) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun isoFromProgress(progress: Int, range: Range<Int>): Int {
        val span = range.upper - range.lower
        return (range.lower + (progress.toFloat() / SEEK_MAX * span).toInt()).coerceIn(range.lower, range.upper)
    }

    // Shutter speed: logarithmic, since the range spans nanoseconds to (up to)
    // a full second and a linear slider would cram all the usable speeds into
    // a tiny sliver.
    private fun progressFromExposureNs(ns: Long, range: Range<Long>): Int {
        val logMin = ln(range.lower.toDouble())
        val logMax = ln(range.upper.toDouble())
        val span = (logMax - logMin).takeIf { it > 0 } ?: 1.0
        val logVal = ln(ns.toDouble().coerceIn(range.lower.toDouble(), range.upper.toDouble()))
        return (((logVal - logMin) / span) * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
    }

    private fun exposureNsFromProgress(progress: Int, range: Range<Long>): Long {
        val logMin = ln(range.lower.toDouble())
        val logMax = ln(range.upper.toDouble())
        val logVal = logMin + (progress.toDouble() / SEEK_MAX) * (logMax - logMin)
        return exp(logVal).toLong().coerceIn(range.lower, range.upper)
    }

    private fun formatShutterSpeed(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            "1/${denominator}s"
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle / permissions
    // -----------------------------------------------------------------------

    private fun openCameraIfReady() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (textureView.isAvailable) {
                cameraController.openCamera(textureView, switchRaw.isChecked)
            }
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            openCameraIfReady()
        }
    }

    override fun onPause() {
        cameraController.closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        cameraController.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SEEK_MAX = 1000
        private const val ONE_SECOND_NS = 1_000_000_000L
    }
}
