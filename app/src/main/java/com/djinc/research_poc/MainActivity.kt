package com.djinc.research_poc

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageCaptureException
import androidx.core.view.WindowCompat
import com.djinc.research_poc.databinding.ActivityMainBinding
import java.lang.Integer.parseInt
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camera: Camera
    private var imageCapture: ImageCapture? = null
    private var cameraInfo: Camera2CameraInfo? = null
    private var cameraControl: Camera2CameraControl? = null

    data class WhiteBalanceMode(val name: String, val value: Int)

    private var whiteBalanceModes = listOf(
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

        // Set slider listeners
        viewBinding.exposureUpButton.setOnClickListener { setEffect(EFFECT.EXPOSURE, 1) }
        viewBinding.exposureDownButton.setOnClickListener { setEffect(EFFECT.EXPOSURE, 0) }

        viewBinding.whiteBalanceUpButton.setOnClickListener { setEffect(EFFECT.WHITEBALANCE, 1) }
        viewBinding.whiteBalanceDownButton.setOnClickListener { setEffect(EFFECT.WHITEBALANCE, 0) }

        cameraExecutor = Executors.newSingleThreadExecutor()

        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraPOC")
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
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setEffect(effect: EFFECT, value: Int) {
        val cameraInfo = cameraInfo ?: return
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
                EFFECT.ZOOM -> {
                    
                }
            }
        }.build()

        cameraControl.addCaptureRequestOptions(options)
            .addListener({}, ContextCompat.getMainExecutor(this))
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
        ZOOM,
    }
}