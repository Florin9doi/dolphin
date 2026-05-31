// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.Surface
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.features.settings.model.StringSetting
import org.dolphinemu.dolphinemu.utils.PermissionsHandler

class CameraHelper {
    companion object {
        val TAG = "CameraHelper"
        private var instance: CameraHelper? = null
        private lateinit var handler: Handler
        private lateinit var camera: Camera
        private val cameraInfo = CameraInfo()
        private lateinit var previewSize: Camera.Size
        private var surfaceTexture: SurfaceTexture? = null
        private lateinit var display: Display
        private var cameraEntries = arrayOf<String>()
        private var cameraValues = arrayOf<String>()
        private var width = 0
        private var height = 0
        private var virtualCamRunning: Boolean = false
        private var hostCamRunning: Boolean = false

        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: CameraHelper().also {
                instance = it
                handler = Handler(DolphinApplication.getAppContext().mainLooper)
                surfaceTexture = SurfaceTexture(5)

                fun getCameraDescription(facing: Int) : String {
                    return when (facing) {
                        CameraInfo.CAMERA_FACING_BACK -> "Back"
                        CameraInfo.CAMERA_FACING_FRONT -> "Front"
                        else -> "Unknown"
                    }
                }
                for (cameraId in 0 until Camera.getNumberOfCameras()) {
                    val cameraInfo = CameraInfo()
                    Camera.getCameraInfo(cameraId, cameraInfo)
                    cameraEntries += "${cameraId}: ${getCameraDescription(cameraInfo.facing)}"
                    cameraValues += "${cameraId}"
                }
            }
        }

        fun onStart() {
            handler.postDelayed(object : Runnable {
                override fun run() {
                    resumeCamera()
                }
            }, 500)
        }

        fun onStop() {
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
            Log.i(TAG, "startCamera: ${width}x${height}")
            this.width = width
            this.height = height
            if (!PermissionsHandler.hasCameraAccess(DolphinApplication.getAppContext())) {
                PermissionsHandler.requestCameraPermission(DolphinApplication.getAppActivity())
                return
            }
            virtualCamRunning = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display = DolphinApplication.getAppContext().display!!
            } else {
                display = DolphinApplication.getAppActivity().windowManager.defaultDisplay
            }
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

            var cameraId = Integer.parseInt(StringSetting.MAIN_SELECTED_CAMERA.string)
            Camera.getCameraInfo(cameraId, cameraInfo)
            camera = Camera.open(cameraId)
            val param: Camera.Parameters = camera.getParameters()

            // step 1: start finding the largest preview
            previewSize = param.getSupportedPreviewSizes()[0]
            for (preview in param.getSupportedPreviewSizes()) {
                if (preview.width > previewSize.width || preview.height > previewSize.height) {
                    previewSize = preview
                }
            }
            // step 2: find the smallest preview which can fit the expected size
            var rotation = getRotation()
            for (preview in param.getSupportedPreviewSizes()) {
                Log.i(TAG, "supportedPreviewSize : ${preview.width}x${preview.height}")
                if ((preview.width < previewSize.width || preview.height < previewSize.height) &&
                    (((rotation ==  0 || rotation == 180) && preview.width >= width && preview.height >= height) ||
                            ((rotation == 90 || rotation == 270) && preview.width >= height && preview.height >= width))) {
                    previewSize = preview
                }
            }
            Log.i(TAG, "previewSize : ${previewSize.width}x${previewSize.height}")
            param.setPreviewSize(previewSize.width, previewSize.height)
            camera.setParameters(param)
            camera.setPreviewTexture(surfaceTexture)
            camera.setPreviewCallback(previewCallback)
            camera.startPreview()
        }

        private fun pauseCamera() {
            if (!hostCamRunning)
                return
            Log.i(TAG, "pauseCamera")
            hostCamRunning = false

            camera.setPreviewCallback(null)
            camera.stopPreview()
            camera.release()
        }

        fun getRotation(): Int {
            val displayRotation = when (display.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return (360 + cameraInfo.orientation - displayRotation) % 360
            } else {
                return (360 + cameraInfo.orientation + displayRotation) % 360
            }
        }

        object previewCallback: Camera.PreviewCallback {
            override fun onPreviewFrame(nv21data: ByteArray, camera: Camera) {
                var rotation = getRotation()

                // Convert NV21 to YUY2
                val yuy2Image = ByteArray(2 * width * height)
                if (rotation == 0 || rotation == 180) {
                    for (line in 0 until height) {
                        val yLine = if (rotation == 0) line else (height-1-line)
                        val uvLine = previewSize.height + if (rotation == 0) (line / 2) else ((height-1-line) / 2)
                        for (col in 0 until width) {
                            val yuy2Pos = 2 * (width * line + col)
                            val yCol = if (rotation == 0) (col) else (width-1-col)
                            val uvCol = if (rotation == 0) (col and 1.inv()) else (width-1-col and 1.inv())
                            val yPos = previewSize.width * yLine  + yCol
                            val uPos = previewSize.width * uvLine + uvCol + 1
                            val vPos = previewSize.width * uvLine + uvCol
                            yuy2Image.set(yuy2Pos, nv21data.get(yPos))
                            yuy2Image.set(yuy2Pos + 1, if (col % 2 == 0) nv21data.get(uPos)
                                                                    else nv21data.get(vPos))
                        }
                    }
                } else if (rotation == 90 || rotation == 270) {
                    for (line in 0 until height) {
                        val yCol = if (rotation == 90) line else (width-1-line)
                        val uvCol = if (rotation == 90) (line and 1.inv()) else (width-1-line and 1.inv())
                        for (col in 0 until width) {
                            val yuy2Pos = 2 * (width * line + col)
                            val yLine = if (rotation == 90) (previewSize.height-1-col) else col
                            val uvLine = previewSize.height + if (rotation == 90) ((previewSize.height-1-col) / 2) else (col / 2)
                            val yPos = previewSize.width * yLine  + yCol
                            val uPos = previewSize.width * uvLine + uvCol + 1
                            val vPos = previewSize.width * uvLine + uvCol
                            yuy2Image.set(yuy2Pos, nv21data.get(yPos))
                            yuy2Image.set(yuy2Pos + 1, if (col % 2 == 0) nv21data.get(uPos)
                            else nv21data.get(vPos))
                        }
                    }
                }
                NativeLibrary.CameraSetData(yuy2Image)
            }
        }
    }
}
