package com.djinc.research_poc

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View.VISIBLE
import android.view.View.GONE
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import com.djinc.research_poc.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camera: Camera
    private var imageCapture: ImageCapture? = null
    private var cameraInfo: Camera2CameraInfo? = null
    private var cameraControl: Camera2CameraControl? = null

    data class WhiteBalanceMode(val name: String, val value: Int)

    private var whiteBalanceModes = listOf(
        WhiteBalanceMode("Off", CaptureRequest.CONTROL_AWB_MODE_OFF),
        WhiteBalanceMode("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
        WhiteBalanceMode("Incandescent", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
        WhiteBalanceMode("Fluorescent", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
        WhiteBalanceMode("Warm fluorescent", CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT),
        WhiteBalanceMode("Daylight", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
        WhiteBalanceMode("Cloudy daylight", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
        WhiteBalanceMode("Twilight", CaptureRequest.CONTROL_AWB_MODE_TWILIGHT),
        WhiteBalanceMode("Shade", CaptureRequest.CONTROL_AWB_MODE_SHADE),
    )
    private var activeWhiteBalanceIndex = 0

    private var chromaticMode = false
    private var distortionMode = false
    private var edgeMode = false
    private var hotPixelMode = false
    private var jpegQualityMode = false
    private var stabilisationMode = false
    private var noiseMode = false
    private var toneMappingMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.activeWhiteBalance.text = whiteBalanceModes[activeWhiteBalanceIndex].name

        // Set up the listener for taking a photo
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }

        // Set effect listeners
        viewBinding.exposureUpButton.setOnClickListener { setEffect(EFFECT.EXPOSURE, 1) }
        viewBinding.exposureDownButton.setOnClickListener { setEffect(EFFECT.EXPOSURE, 0) }

        viewBinding.whiteBalanceUpButton.setOnClickListener { setEffect(EFFECT.WHITEBALANCE, 1) }
        viewBinding.whiteBalanceDownButton.setOnClickListener { setEffect(EFFECT.WHITEBALANCE, 0) }

        // Set extra effect listeners
        viewBinding.drawerToggle.setOnClickListener {
            val drawer = viewBinding.drawer
            drawer.visibility = if (drawer.isVisible) GONE else VISIBLE
        }
        viewBinding.chromaticMode.setOnClickListener { setMode(MODE.CHROMATIC) }
        viewBinding.distortionMode.setOnClickListener { setMode(MODE.DISTORTION) }
        viewBinding.edgeMode.setOnClickListener { setMode(MODE.EDGE) }
        viewBinding.hotPixelMode.setOnClickListener { setMode(MODE.HOT_PIXEL) }
        viewBinding.jpegQualityMode.setOnClickListener { setMode(MODE.JPEG_QUALITY) }
        viewBinding.stabilisationMode.setOnClickListener { setMode(MODE.STABILISATION) }
        viewBinding.noiseReductionMode.setOnClickListener { setMode(MODE.NOISE) }
        viewBinding.toneMappingMode.setOnClickListener { setMode(MODE.TONE_MAPPING) }

        cameraExecutor = Executors.newSingleThreadExecutor()

        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        Log.d(TAG, "Capturing photo")

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "pictures/GrowCollect"
                )
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .apply {

                }
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                camera.let {
                    cameraInfo = Camera2CameraInfo.from(it.cameraInfo)
                    cameraControl = Camera2CameraControl.from(it.cameraControl)
                }

                // Setup zoom gesture detector
                val scaleGestureDetector = ScaleGestureDetector(this,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            val scale =
                                camera.cameraInfo.zoomState.value!!.zoomRatio * detector.scaleFactor
                            camera.cameraControl.setZoomRatio(scale)
                            return true
                        }
                    })

                viewBinding.viewFinder.setOnTouchListener { view, event ->
                    view.performClick()
                    scaleGestureDetector.onTouchEvent(event)
                    return@setOnTouchListener true
                }

//                checkAvailableExtraEffects()

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun checkAvailableExtraEffects() {
        val cameraInfo = cameraInfo ?: return

        val chromaticModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        Log.d(TAG, "Chromatic modes: ${chromaticModes?.joinToString(",")}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val distortionModes =
                cameraInfo.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            Log.d(TAG, "Distortion correction modes: ${distortionModes?.joinToString(",")}")
        } else {
            Log.d(TAG, "Distortion correction not supported")
        }

        val edgeModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        Log.d(TAG, "Edge modes: ${edgeModes?.joinToString(",")}")

        val hotPixelModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
        Log.d(TAG, "Hot pixel modes: ${hotPixelModes?.joinToString(",")}")

        // JPEQ quality is always supported

        val stabilisationModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        Log.d(TAG, "Stabilisation modes: ${stabilisationModes?.joinToString(",")}")

        val noiseModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        Log.d(TAG, "Noise reduction modes: ${noiseModes?.joinToString(",")}")

        val toneMappingModes =
            cameraInfo.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        Log.d(TAG, "Tone mapping modes: ${toneMappingModes?.joinToString(",")}")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setEffect(effect: EFFECT, value: Int) {
        val cameraControl = cameraControl ?: return

        val options = CaptureRequestOptions.Builder().apply {
            // Disable scene mode to customize camera options
            setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE,
                CameraMetadata.CONTROL_SCENE_MODE_DISABLED
            )
            when (effect) {
                EFFECT.EXPOSURE -> {
                    if (!camera.cameraInfo.exposureState.isExposureCompensationSupported) return

                    val currentIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
                    val range = camera.cameraInfo.exposureState.exposureCompensationRange
                    val newIndex = if (value > 0) currentIndex + 1 else currentIndex - 1

                    if (newIndex in range) camera.cameraControl.setExposureCompensationIndex(
                        newIndex
                    )
                }
                EFFECT.WHITEBALANCE -> {
                    var newIndex =
                        if (value > 0) activeWhiteBalanceIndex + 1 else activeWhiteBalanceIndex - 1
                    if (newIndex > whiteBalanceModes.lastIndex) newIndex = 0
                    if (newIndex < 0) newIndex = whiteBalanceModes.lastIndex
                    val newWhiteBalanceMode = whiteBalanceModes[newIndex]
                    activeWhiteBalanceIndex = newIndex
                    setCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_MODE,
                        newWhiteBalanceMode.value
                    )
                    viewBinding.activeWhiteBalance.text = newWhiteBalanceMode.name
                }
            }
        }.build()

        cameraControl.addCaptureRequestOptions(options)
            .addListener({}, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setMode(mode: MODE) {
        val cameraControl = cameraControl ?: return

        val options = CaptureRequestOptions.Builder().apply {
            when (mode) {
                MODE.CHROMATIC -> {
                    setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        if (chromaticMode) CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST else CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                    )
                    chromaticMode = !chromaticMode
                    toggleModeColor(viewBinding.chromaticMode, chromaticMode)
                }
                MODE.DISTORTION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setCaptureRequestOption(
                            CaptureRequest.DISTORTION_CORRECTION_MODE,
                            if (distortionMode) CaptureRequest.DISTORTION_CORRECTION_MODE_FAST else CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                        )
                        distortionMode = !distortionMode
                        toggleModeColor(viewBinding.distortionMode, distortionMode)
                    }
                }
                MODE.EDGE -> {
                    setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE,
                        if (edgeMode) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_HIGH_QUALITY
                    )
                    edgeMode = !edgeMode
                    toggleModeColor(viewBinding.edgeMode, edgeMode)
                }
                MODE.HOT_PIXEL -> {
                    setCaptureRequestOption(
                        CaptureRequest.HOT_PIXEL_MODE,
                        if (hotPixelMode) CaptureRequest.HOT_PIXEL_MODE_FAST else CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                    )
                    hotPixelMode = !hotPixelMode
                    toggleModeColor(viewBinding.hotPixelMode, hotPixelMode)
                }
                MODE.JPEG_QUALITY -> {
                    setCaptureRequestOption(
                        CaptureRequest.JPEG_QUALITY,
                        if (jpegQualityMode) 90 else 100
                    )
                    jpegQualityMode = !jpegQualityMode
                    toggleModeColor(viewBinding.jpegQualityMode, jpegQualityMode)
                }
                MODE.STABILISATION -> {
                    setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        if (stabilisationMode) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    stabilisationMode = !stabilisationMode
                    toggleModeColor(viewBinding.stabilisationMode, stabilisationMode)
                }
                MODE.NOISE -> {
                    setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        if (noiseMode) CaptureRequest.NOISE_REDUCTION_MODE_FAST else CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    )
                    noiseMode = !noiseMode
                    toggleModeColor(viewBinding.noiseReductionMode, noiseMode)
                }
                MODE.TONE_MAPPING -> {
                    setCaptureRequestOption(
                        CaptureRequest.TONEMAP_MODE,
                        if (toneMappingMode) CaptureRequest.TONEMAP_MODE_FAST else CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
                    )
                    toneMappingMode = !toneMappingMode
                    toggleModeColor(viewBinding.toneMappingMode, toneMappingMode)
                }
            }
        }.build()

        cameraControl.addCaptureRequestOptions(options)
            .addListener({}, ContextCompat.getMainExecutor(this))
    }

    private fun toggleModeColor(button: Button, state: Boolean) {
        button.setBackgroundColor(
            if (state) Color.parseColor(
                "#65AC1E"
            ) else Color.parseColor("#CCCCCC")
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ResearchPOC"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    enum class EFFECT {
        EXPOSURE,
        WHITEBALANCE,
    }

    enum class MODE {
        CHROMATIC,
        DISTORTION,
        EDGE,
        HOT_PIXEL,
        JPEG_QUALITY,
        STABILISATION,
        NOISE,
        TONE_MAPPING,
    }
}