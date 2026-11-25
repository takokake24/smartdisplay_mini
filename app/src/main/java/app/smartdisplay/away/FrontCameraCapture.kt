package app.smartdisplay.away

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * インカメラで静止画を1枚撮る。
 * シャッター音は鳴らさない。端末が撮影音を強制する場合に備え、撮影中だけシステム音量を一時的に下げる。
 */
class FrontCameraCapture(private val context: Context) {
    private val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    private val thread = HandlerThread("away-alert-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val captureLatch = AtomicReference<CountDownLatch?>(null)
    private val pendingBytes = AtomicReference<ByteArray?>(null)
    private val acceptImage = AtomicBoolean(false)

    @Volatile
    private var characteristics: CameraCharacteristics? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var savedSystemVolume: Int? = null

    fun isOpen(): Boolean = ready.get() && !closed.get()

    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        val cameraManager = manager ?: return false
        if (closed.get()) {
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val cameraId = frontCameraId() ?: return false
        val size = chooseSize(cameraId)
        val ticket = generation.incrementAndGet()
        val latch = CountDownLatch(1)
        var opened = false
        val posted = handler.post {
            if (ticket != generation.get() || closed.get()) {
                latch.countDown()
                return@post
            }
            try {
                releaseDevices()
                val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                reader = imageReader
                imageReader.setOnImageAvailableListener({ source ->
                    val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                    var accepted = false
                    try {
                        accepted = acceptImage.compareAndSet(true, false)
                        if (!accepted) {
                            return@setOnImageAvailableListener
                        }
                        val buffer = image.planes[0].buffer
                        buffer.rewind()
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        pendingBytes.set(bytes)
                    } catch (_: Exception) {
                        pendingBytes.set(null)
                    } finally {
                        image.close()
                        if (accepted) {
                            captureLatch.get()?.countDown()
                        }
                    }
                }, handler)
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (ticket != generation.get() || closed.get()) {
                                camera.close()
                                latch.countDown()
                                return
                            }
                            device = camera
                            try {
                                @Suppress("DEPRECATION")
                                camera.createCaptureSession(
                                    listOf(imageReader.surface),
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(captureSession: CameraCaptureSession) {
                                            if (ticket != generation.get() || closed.get()) {
                                                captureSession.close()
                                                latch.countDown()
                                                return
                                            }
                                            session = captureSession
                                            ready.set(true)
                                            opened = true
                                            latch.countDown()
                                        }

                                        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                            ready.set(false)
                                            latch.countDown()
                                        }
                                    },
                                    handler,
                                )
                            } catch (_: Exception) {
                                camera.close()
                                latch.countDown()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            ready.set(false)
                            camera.close()
                            if (device == camera) {
                                device = null
                                session = null
                            }
                            latch.countDown()
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            ready.set(false)
                            camera.close()
                            if (device == camera) {
                                device = null
                                session = null
                            }
                            latch.countDown()
                        }
                    },
                    handler,
                )
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        if (!posted) {
            return false
        }
        if (!await(latch, OpenTimeoutSeconds)) {
            generation.incrementAndGet()
            ready.set(false)
            return false
        }
        return opened && ready.get()
    }

    fun capture(dest: java.io.File): Boolean {
        if (closed.get() || !ready.get()) {
            return false
        }
        val orientation = jpegOrientation()
        val latch = CountDownLatch(1)
        captureLatch.set(latch)
        pendingBytes.set(null)
        silenceShutter()
        try {
            val posted = handler.post {
                val currentSession = session
                val currentReader = reader
                val currentDevice = device
                if (closed.get() || currentSession == null || currentReader == null || currentDevice == null) {
                    latch.countDown()
                    return@post
                }
                try {
                    drain(currentReader)
                    acceptImage.set(true)
                    val request = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(currentReader.surface)
                        set(CaptureRequest.JPEG_QUALITY, JpegQuality.toByte())
                        set(CaptureRequest.JPEG_ORIENTATION, orientation)
                    }
                    currentSession.capture(
                        request.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: android.hardware.camera2.CaptureFailure,
                            ) {
                                acceptImage.set(false)
                                latch.countDown()
                            }
                        },
                        handler,
                    )
                } catch (_: Exception) {
                    acceptImage.set(false)
                    latch.countDown()
                }
            }
            if (!posted || !await(latch, CaptureTimeoutSeconds)) {
                handler.post { acceptImage.set(false) }
                return false
            }
            val bytes = pendingBytes.getAndSet(null) ?: return false
            return writeReduced(bytes, dest, orientation)
        } finally {
            restoreShutter()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        generation.incrementAndGet()
        ready.set(false)
        captureLatch.get()?.countDown()
        val done = CountDownLatch(1)
        val posted = handler.post {
            acceptImage.set(false)
            releaseDevices()
            done.countDown()
        }
        if (posted) {
            await(done, CloseTimeoutSeconds)
        }
        thread.quitSafely()
    }

    private fun frontCameraId(): String? {
        val ids = runCatching { manager?.cameraIdList }.getOrNull() ?: return null
        return ids.firstOrNull { id ->
            val chars = runCatching { manager?.getCameraCharacteristics(id) }.getOrNull()
            val facing = chars?.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                characteristics = chars
                true
            } else {
                false
            }
        }
    }

    private fun chooseSize(cameraId: String): Size {
        val chars = characteristics ?: manager?.getCameraCharacteristics(cameraId)
        characteristics = chars
        val sizes = chars
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.toList()
            .orEmpty()
        val modest = sizes.filter { max(it.width, it.height) in 640..1600 }
        return modest.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    private fun jpegOrientation(): Int {
        val chars = characteristics ?: return 0
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        @Suppress("DEPRECATION")
        val rotation = context.getSystemService(WindowManager::class.java)
            ?.defaultDisplay
            ?.rotation
            ?: Surface.ROTATION_0
        var device = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            device = -device
        }
        return (sensor + device + 360) % 360
    }

    private fun drain(imageReader: ImageReader) {
        while (true) {
            val image = imageReader.acquireNextImage() ?: break
            image.close()
        }
    }

    private fun releaseDevices() {
        ready.set(false)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
    }

    private fun silenceShutter() {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (savedSystemVolume != null) {
            return
        }
        val current = runCatching { audio.getStreamVolume(AudioManager.STREAM_SYSTEM) }.getOrNull() ?: return
        savedSystemVolume = current
        runCatching { audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0) }
    }

    private fun restoreShutter() {
        val saved = savedSystemVolume ?: return
        savedSystemVolume = null
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_SYSTEM, saved, 0) }
    }

    private fun await(latch: CountDownLatch, seconds: Long): Boolean {
        return try {
            latch.await(seconds, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private data class ImageTurn(val degrees: Int, val mirror: Boolean)

    companion object {
        private const val OpenTimeoutSeconds = 5L
        private const val CaptureTimeoutSeconds = 6L
        private const val CloseTimeoutSeconds = 2L
        private const val JpegQuality = 70
        private const val TargetLongEdge = 1280

        private fun writeReduced(bytes: ByteArray, dest: java.io.File, fallbackDegrees: Int): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return false
            }
            val exif = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
            val turn = turnOf(exif, fallbackDegrees)
            var sample = 1
            val edge = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (edge / (sample * 2) >= TargetLongEdge) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return false
            val turned = applyTurn(decoded, turn)
            val longEdge = max(turned.width, turned.height).coerceAtLeast(1)
            val output = if (longEdge <= TargetLongEdge) {
                turned
            } else {
                val ratio = TargetLongEdge.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    turned,
                    (turned.width * ratio).toInt().coerceAtLeast(1),
                    (turned.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
            val written = runCatching {
                dest.outputStream().use { stream ->
                    output.compress(Bitmap.CompressFormat.JPEG, JpegQuality, stream)
                }
            }.getOrDefault(false)
            if (output !== turned) {
                turned.recycle()
            }
            if (turned !== decoded) {
                decoded.recycle()
            }
            output.recycle()
            return written && dest.length() > 0L
        }

        private fun turnOf(exif: Int, fallbackDegrees: Int): ImageTurn {
            return when (exif) {
                ExifInterface.ORIENTATION_NORMAL -> ImageTurn(0, false)
                ExifInterface.ORIENTATION_ROTATE_90 -> ImageTurn(90, false)
                ExifInterface.ORIENTATION_ROTATE_180 -> ImageTurn(180, false)
                ExifInterface.ORIENTATION_ROTATE_270 -> ImageTurn(270, false)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ImageTurn(0, true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> ImageTurn(180, true)
                ExifInterface.ORIENTATION_TRANSPOSE -> ImageTurn(90, true)
                ExifInterface.ORIENTATION_TRANSVERSE -> ImageTurn(270, true)
                else -> ImageTurn(fallbackDegrees, false)
            }
        }

        private fun applyTurn(bitmap: Bitmap, turn: ImageTurn): Bitmap {
            if (turn.degrees % 360 == 0 && !turn.mirror) {
                return bitmap
            }
            val matrix = Matrix().apply {
                if (turn.mirror) {
                    postScale(-1f, 1f)
                }
                if (turn.degrees % 360 != 0) {
                    postRotate(turn.degrees.toFloat())
                }
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
