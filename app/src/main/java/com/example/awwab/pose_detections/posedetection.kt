package com.example.awwab.pose_detections

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

// ====== CONFIG ======
private const val INPUT_SIZE = 640
private const val NUM_KEYPOINTS = 17
private const val NUM_ANCHORS = 8400
private const val MODEL_ASSET_PATH = "model/yolov8n-pose_float32.tflite"

// Output shape expected by your model: [1][56][8400]
private val OUTPUT_BUFFER = Array(1) { Array(56) { FloatArray(NUM_ANCHORS) } }

// ====== POSE ANALYZER ======
class PoseAnalyzer(
    private val context: Context,
    private val listener: (pose: String, color: Int, keypoints: List<PointF>) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "PoseAnalyzer"
    }

    // Single-thread executor for inference and heavy conversion work
    private val inferExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Interpreter - lazy init in initialize()
    private var interpreter: Interpreter? = null

    // Input ByteBuffer reused per-frame (FLOAT32)
    private val inputByteBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())

    // Busy flag to drop frames when inference is running
    @Volatile
    private var isBusy = false

    // Model loaded flag
    @Volatile
    private var isReady = false

    // Helper: check whether the asset exists (returns true if it can be opened)
    private fun assetExists(): Boolean {
        return try {
            context.assets.open(MODEL_ASSET_PATH).close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "assetExists: cannot open $MODEL_ASSET_PATH: ${e.message}")
            false
        }
    }

    // Initialize model (call before starting camera)
    fun initialize(onReady: (() -> Unit)? = null) {
        // run in background
        inferExecutor.execute {
            try {
                if (!assetExists()) {
                    Log.e(TAG, "Model asset not found: $MODEL_ASSET_PATH")
                    Log.e(TAG, "Place the TFLite model under: app/src/main/assets/$MODEL_ASSET_PATH")
                    isReady = false
                    onReady?.invoke()
                    return@execute
                }

                val f = FileUtil.loadMappedFile(context, MODEL_ASSET_PATH)
                val options = Interpreter.Options()
                // Use a small number of threads by default; adjust if needed
                options.setNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
                // enable XNNPACK where supported (safer on newer TF Lite)
                try {
                    options.setUseXNNPACK(true)
                } catch (_: Throwable) {
                    // some TF Lite versions may not expose setUseXNNPACK; ignore safely
                }

                interpreter = Interpreter(f, options)

                // Warmup with zeros (ensure correct size)
                val warmup = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
                warmup.rewind()
                repeat(INPUT_SIZE * INPUT_SIZE * 3) { warmup.putFloat(0.0f) }
                warmup.rewind()

                // Guard run with non-null interpreter
                interpreter?.run(warmup, OUTPUT_BUFFER)

                isReady = true
                Log.i(TAG, "Interpreter initialized and ready")
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize interpreter: ${e.message}")
                e.printStackTrace()
                isReady = false
            }
        }
    }

    // Close and cleanup
    fun close() {
        inferExecutor.execute {
            try {
                interpreter?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                interpreter = null
                isReady = false
            }
        }
        inferExecutor.shutdownNow()
    }

    // Analyzer entrypoint
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // Quick checks - drop early if model not ready or already busy
        if (!isReady || isBusy) {
            imageProxy.close()
            return
        }

        // Acquire image and delegate work to background executor
        val img = imageProxy.image
        if (img == null) {
            imageProxy.close()
            return
        }

        // guard interpreter exists
        val localInterpreter = interpreter
        if (localInterpreter == null) {
            Log.w(TAG, "analyze: interpreter is null despite isReady=$isReady")
            imageProxy.close()
            return
        }

        isBusy = true

        // Copy rotation (0 / 90 / 180 / 270)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        inferExecutor.execute {
            try {
                // 1) Convert YUV420 to normalized FLOAT32 ByteBuffer, resized to INPUT_SIZE x INPUT_SIZE
                // We fill inputByteBuffer (reused) — must rewind before writing
                inputByteBuffer.rewind()
                convertYUV420ToFloatBuffer(img, rotationDegrees, inputByteBuffer)
                inputByteBuffer.rewind()

                // 2) Run inference — interpreter expects input ByteBuffer and output object with same shape
                localInterpreter.run(inputByteBuffer, OUTPUT_BUFFER)

                // 3) Parse output into keypoints
                val keypoints = parseKeypoints(OUTPUT_BUFFER[0])

                // 4) Classify pose (your existing logic translated)
                val poseResult = classifyPose(keypoints)

                // 5) Post result on UI (listener typically updates Compose state)
                listener(poseResult.pose, poseResult.color, keypoints)
            } catch (e: Exception) {
                Log.e(TAG, "Error during inference: ${e.message}")
                e.printStackTrace()
            } finally {
                // release and allow next frame
                isBusy = false
                imageProxy.close()
            }
        }
    }

    /**
     * Fast YUV420 -> RGB normalized float conversion with nearest neighbor resize.
     * - image: android.media.Image (YUV_420_888)
     * - rotationDegrees: rotation to apply (camera sensor)
     * - out: ByteBuffer (FLOAT32) to write in RGB order (R,G,B) normalized to [0..1]
     *
     * This function avoids allocations and works per-pixel with integer arithmetic.
     */
    private fun convertYUV420ToFloatBuffer(
        image: android.media.Image,
        rotationDegrees: Int,
        out: ByteBuffer
    ) {
        // Source dims
        val srcW = image.width
        val srcH = image.height

        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        // Pre-calc scale (target is INPUT_SIZE)
        val outW = INPUT_SIZE
        val outH = INPUT_SIZE
        val scaleX = srcW.toFloat() / outW.toFloat()
        val scaleY = srcH.toFloat() / outH.toFloat()

        // We'll sample nearest neighbor from source using scaleX/scaleY
        // We'll also handle rotation by mapping target coords back to source coords before sampling
        for (oy in 0 until outH) {
            for (ox in 0 until outW) {
                // Map target coordinate to source coordinate before rotation
                val srcXf = (ox * scaleX)
                val srcYf = (oy * scaleY)
                // nearest
                val sx0 = srcXf.toInt().coerceIn(0, srcW - 1)
                val sy0 = srcYf.toInt().coerceIn(0, srcH - 1)

                // Apply rotation mapping to sample coordinates (inverse rotation)
                val (sx, sy) = when (rotationDegrees % 360) {
                    0 -> Pair(sx0, sy0)
                    90 -> Pair(sy0, srcW - 1 - sx0)
                    180 -> Pair(srcW - 1 - sx0, srcH - 1 - sy0)
                    270 -> Pair(srcH - 1 - sy0, sx0)
                    else -> Pair(sx0, sy0)
                }

                // Safe-check indices
                val sxClamped = sx.coerceIn(0, srcW - 1)
                val syClamped = sy.coerceIn(0, srcH - 1)

                // Y index
                val yIndex = syClamped * yRowStride + sxClamped
                val y = (yPlane.get(yIndex).toInt() and 0xFF)

                // UV index (for subsampled planes)
                val uvx = sxClamped / 2
                val uvy = syClamped / 2
                val uvIndex = uvy * uvRowStride + uvx * uvPixelStride

                // Bounds guard for UV
                val u = try {
                    (uPlane.get(uvIndex).toInt() and 0xFF)
                } catch (_: IndexOutOfBoundsException) {
                    128
                }
                val v = try {
                    (vPlane.get(uvIndex).toInt() and 0xFF)
                } catch (_: IndexOutOfBoundsException) {
                    128
                }

                // Convert YUV to RGB (fast integer approx)
                val uf = u - 128
                val vf = v - 128
                var r = (y + (1.370705f * vf)).toInt()
                var g = (y - (0.337633f * uf + 0.698001f * vf)).toInt()
                var b = (y + (1.732446f * uf)).toInt()

                // Clamp
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                // Normalize [0..1] float and put into ByteBuffer as float
                out.putFloat(r / 255.0f)
                out.putFloat(g / 255.0f)
                out.putFloat(b / 255.0f)
            }
        }
        out.rewind()
    }

    /**
     * Parse keypoints using the same approach as your Flutter code:
     * - Find the best-scoring anchor from output[4] (scores)
     * - For each keypoint k, read x from output[5 + k*3] and y from output[5 + k*3 + 1], using the chosen anchor index
     *
     * Returns list of PointF (x,y) in normalized coordinates (0..1)
     */
    private fun parseKeypoints(output: Array<FloatArray>): List<PointF> {
        // output: Array(56) of FloatArray(NUM_ANCHORS)
        if (output.size < 6) return emptyList()

        val scoreData = output.getOrNull(4) ?: return emptyList()
        var maxScore = -Float.MAX_VALUE
        var bestAnchor = -1

        for (i in 0 until min(NUM_ANCHORS, scoreData.size)) {
            val s = scoreData[i]
            if (s > maxScore) {
                maxScore = s
                bestAnchor = i
                if (s > 0.6f) break // early exit - prefers strong detections
            }
        }

        if (bestAnchor == -1 || maxScore < 0.2f) {
            return emptyList()
        }

        val points = MutableList(NUM_KEYPOINTS) { PointF(0f, 0f) }

        // The model layout in your Flutter code used 5 + k*3 indices
        for (k in 0 until NUM_KEYPOINTS) {
            val idxX = 5 + (k * 3)
            val idxY = idxX + 1
            if (idxX >= output.size || idxY >= output.size) {
                // safety
                points[k] = PointF(0f, 0f)
            } else {
                val px = output[idxX].getOrNull(bestAnchor) ?: 0f
                val py = output[idxY].getOrNull(bestAnchor) ?: 0f

                // Normalize coordinates to [0..1] if they are in pixel range
                var nx = px
                var ny = py
                if (nx > 1f || ny > 1f) {
                    nx = nx / INPUT_SIZE.toFloat()
                    ny = ny / INPUT_SIZE.toFloat()
                }

                // clamp
                nx = nx.coerceIn(0f, 1f)
                ny = ny.coerceIn(0f, 1f)

                points[k] = PointF(nx, ny)
            }
        }
        return points
    }

    /**
     * Minimal pose classification (translate your SalahLogic)
     * You may replace this with your detailed logic from Flutter.
     */
    private fun classifyPose(keypoints: List<PointF>): PoseResult {
        if (keypoints.size < NUM_KEYPOINTS) return PoseResult("Waiting...", android.graphics.Color.GRAY)

        val nose = keypoints.getOrNull(0) ?: PointF(0f, 0f)
        val shoulder = keypoints.getOrNull(6) ?: PointF(0f, 0f)
        val hip = keypoints.getOrNull(12) ?: PointF(0f, 0f)
        val knee = keypoints.getOrNull(14) ?: PointF(0f, 0f)
        val ankle = keypoints.getOrNull(16) ?: PointF(0f, 0f)

        if (shoulder.x == 0f && shoulder.y == 0f) return PoseResult("No Person", android.graphics.Color.GRAY)

        val kneeAng = calculateAngle(hip, knee, ankle)
        val spineAng = calculateSpineAngle(shoulder, hip)
        val isHeadDown = nose.y > hip.y || (nose.y > shoulder.y && spineAng > 70)

        // Use if/else ladder to make control flow explicit for static analysis
        return if (isHeadDown && kneeAng < 110) {
            PoseResult("SUJUD", android.graphics.Color.GREEN)
        } else if (!isHeadDown && kneeAng < 90) {
            PoseResult("JULUS", android.graphics.Color.MAGENTA)
        } else if (spineAng > 35 && kneeAng > 120) {
            PoseResult("RUKU", android.graphics.Color.RED)
        } else if (spineAng < 40 && kneeAng > 130) {
            PoseResult("QIYAM", android.graphics.Color.CYAN)
        } else {
            PoseResult("Unknown", android.graphics.Color.WHITE)
        }
    }

    private fun calculateAngle(p1: PointF, p2: PointF, p3: PointF): Double {
        var angle = kotlin.math.atan2((p3.y - p2.y).toDouble(), (p3.x - p2.x).toDouble()) -
                kotlin.math.atan2((p1.y - p2.y).toDouble(), (p1.x - p2.x).toDouble())
        angle *= (180.0 / Math.PI)
        if (angle < 0) angle += 360.0
        if (angle > 180) angle = 360.0 - angle
        return angle
    }

    private fun calculateSpineAngle(shoulder: PointF, hip: PointF): Double {
        val ref = PointF(hip.x, hip.y - 0.2f) // small offset in normalized coords
        return calculateAngle(ref, hip, shoulder)
    }
}


 data class PoseResult(val pose: String, val color: Int)
