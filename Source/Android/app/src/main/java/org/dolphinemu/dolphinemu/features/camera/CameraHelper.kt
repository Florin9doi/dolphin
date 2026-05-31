// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import org.dolphinemu.dolphinemu.features.settings.model.StringSetting
import org.dolphinemu.dolphinemu.utils.PermissionsHandler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Camera {
    companion object {
        val TAG = "Camera"
        private var instance: Camera? = null

        private var cameraEntries = arrayOf<String>()
        private var cameraValues = arrayOf<String>()

        private var width = 0
        private var height = 0
        private var virtualCamRunning: Boolean = false
        private var hostCamRunning: Boolean = false
        private lateinit var imageCapture: ImageCapture
        private lateinit var imageAnalyzer: ImageAnalysis
        private lateinit var cameraExecutor: ExecutorService
        private lateinit var handler: Handler

        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: Camera().also {
                instance = it
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val cameraInfos = cameraProvider.getAvailableCameraInfos()
                    var index = 0;
                    fun getCameraDescription(type: Int) : String {
                        return when (type) {
                            CameraMetadata.LENS_FACING_BACK -> "Back"
                            CameraMetadata.LENS_FACING_FRONT -> "Front"
                            CameraMetadata.LENS_FACING_EXTERNAL -> "External"
                            else -> "Unknown"
                        }
                    }
                    for (camera in cameraInfos) {
                        cameraEntries += "${index}: ${getCameraDescription(camera.lensFacing)}"
                        cameraValues += index++.toString()
                    }
                }, ContextCompat.getMainExecutor(context))
                cameraExecutor = Executors.newSingleThreadExecutor()
                handler = Handler(DolphinApplication.getAppContext().mainLooper)
            }
        }

        fun onStart() {
            Log.i(TAG, "onStart")
            handler.postDelayed(object : Runnable {
                override fun run() {
                    resumeCamera()
                }
            }, 500)
        }

        fun onStop() {
            Log.i(TAG, "onStop")
            handler.postDelayed(object : Runnable {
                override fun run() {
                    pauseCamera()
                }
            }, 1)
        }

        fun getCameraEntries() = cameraEntries
        fun getCameraValues() = cameraValues

        @JvmStatic
        fun startCamera(width: Int, height: Int) {
            Log.i(TAG, "startCamera: " + Companion.width + "x" + Companion.height)
            this.width = width
            this.height = height
            if (!PermissionsHandler.hasCameraAccess(DolphinApplication.getAppContext())) {
                PermissionsHandler.requestCameraPermission(DolphinApplication.getAppActivity())
                return
            }
            virtualCamRunning = true
            resumeCamera()
        }

        @JvmStatic
        fun stopCamera() {
            Log.i(TAG, "stopCamera")
            pauseCamera()
            virtualCamRunning = false
        }

        @JvmStatic
        fun resumeCamera() {
            Log.i(TAG, "resumeCamera")
            if (!virtualCamRunning || hostCamRunning)
                return
            hostCamRunning = true

            val cameraProviderFuture = ProcessCameraProvider.getInstance(DolphinApplication.getAppContext())
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val cameraSelector = cameraProvider.getAvailableCameraInfos()
                    .get(Integer.parseInt(StringSetting.MAIN_SELECTED_CAMERA.string))
                    .cameraSelector
                val preview = Preview.Builder().build()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(width, height))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ImageProcessor())
                    }

                if (IntSetting.MAIN_EMULATION_ORIENTATION.int < 0) {
                    orientationEventListener.enable()
                }
                cameraProvider.bindToLifecycle(DolphinApplication.getAppActivity() as LifecycleOwner, cameraSelector, preview, imageCapture, imageAnalyzer)
            }, ContextCompat.getMainExecutor(DolphinApplication.getAppContext()))
        }

        private fun pauseCamera() {
            if (!hostCamRunning)
                return
            Log.i(TAG, "pauseCamera")
            hostCamRunning = false

            if (IntSetting.MAIN_EMULATION_ORIENTATION.int < 0) {
                orientationEventListener.disable()
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(DolphinApplication.getAppContext())
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            }, ContextCompat.getMainExecutor(DolphinApplication.getAppContext()))
        }

        private val orientationEventListener by lazy {
            object : OrientationEventListener(DolphinApplication.getAppContext()) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return
                    }
                    val rotation: Int = UseCase.snapToSurfaceRotation(orientation)
                    imageAnalyzer.targetRotation = rotation
                }
            }
        }

        private class ImageProcessor : ImageAnalysis.Analyzer {
            override fun analyze(image: ImageProxy) {
                if (image.format != ImageFormat.YUV_420_888) {
                    Log.e(TAG, "Error: Unhandled image format: ${image.format}")
                    image.close()
                    stopCamera()
                    return
                }
                val cameraRotation = image.imageInfo.rotationDegrees

                Log.i(TAG, "analyze sz=${image.width}x${image.height} / fmt=${image.format} / "
                    + "rot=${image.imageInfo.rotationDegrees} / "
//                    + "px0=${image.planes[0].pixelStride} / px1=${image.planes[1].pixelStride} / px2=${image.planes[2].pixelStride} / "
//                    + "row0=${image.planes[0].rowStride} / row1=${image.planes[1].rowStride} / row2=${image.planes[2].rowStride}"
                )

                // Convert YUV_420_888 to YUY2
                val yuy2Image = ByteArray(2 * width * height)
                if (cameraRotation == 0 || cameraRotation == 180) {
                    for (line in 0 until height) {
                        val yLine = if (cameraRotation == 0) line else (height-1-line)
                        val uvLine = if (cameraRotation == 0) (line / 2) else ((height-1-line) / 2)
                        for (col in 0 until width) {
                            val yuy2Pos = 2 * (width * line + col)
                            val yCol = if (cameraRotation == 0) col else (width-1-col)
                            val uvCol = if (cameraRotation == 0) (col / 2) else ((width-1-col) / 2)
                            val yPos = image.planes[0].rowStride * yLine  + image.planes[0].pixelStride * yCol
                            val uPos = image.planes[1].rowStride * uvLine + image.planes[1].pixelStride * uvCol
                            val vPos = image.planes[2].rowStride * uvLine + image.planes[2].pixelStride * uvCol
                            yuy2Image.set(yuy2Pos, image.planes[0].buffer.get(yPos))
                            yuy2Image.set(yuy2Pos + 1, if (col % 2 == 0) image.planes[1].buffer.get(uPos)
                            else image.planes[2].buffer.get(vPos))
                        }
                    }
                } else if (cameraRotation == 90 || cameraRotation == 270) {
                    for (line in 0 until Math.min(height, image.width)) {
                        val yCol = if (cameraRotation == 90) line else (image.width-1-line)
                        val uvCol = if (cameraRotation == 90) (line / 2) else ((image.width-1-line) / 2)
                        for (col in 0 until Math.min(width, image.height)) {
                            val yuy2Pos = 2 * (width * line + col)
                            val yLine = if (cameraRotation == 90) (image.height-1-col) else col
                            val uvLine = if (cameraRotation == 90) ((image.height-1-col) / 2) else (col / 2)
                            val yPos = image.planes[0].rowStride * yLine  + image.planes[0].pixelStride * yCol
                            val uPos = image.planes[1].rowStride * uvLine + image.planes[1].pixelStride * uvCol
                            val vPos = image.planes[2].rowStride * uvLine + image.planes[2].pixelStride * uvCol
                            yuy2Image.set(yuy2Pos, image.planes[0].buffer.get(yPos))
                            yuy2Image.set(yuy2Pos + 1, if (col % 2 == 0) image.planes[1].buffer.get(uPos)
                            else image.planes[2].buffer.get(vPos))
                        }
                    }

                    for (line in 0 until height) {
                        for (col in image.height until width) {
                            val yuy2Pos = 2 * (width * line + col)
                            yuy2Image.set(yuy2Pos + 1, 127)
                        }
                    }
                }
                image.close()
                NativeLibrary.CameraSetData(yuy2Image)
            }
        }
    }
}
