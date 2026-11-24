package com.memoryreel.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** Relative depth of a photo, 0 = far, 1 = near, on a small grid that covers the whole photo. */
class DepthMap(val width: Int, val height: Int, val data: FloatArray) {
    /** Bilinear lookup; u and v are 0..1 across the photo. */
    fun sample(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val y = (v * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = if (x0 + 1 < width) x0 + 1 else x0
        val y1 = if (y0 + 1 < height) y0 + 1 else y0
        val fx = x - x0
        val fy = y - y0
        val a = data[y0 * width + x0]
        val b = data[y0 * width + x1]
        val c = data[y1 * width + x0]
        val d = data[y1 * width + x1]
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }
}

/**
 * Runs the Depth Anything V2 (small) model on the phone with ONNX Runtime.
 * No internet, no cost: the model ships inside the app (assets/depth.onnx).
 */
class DepthEstimator(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = try {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(bytes, options)
    } catch (e: Throwable) {
        null
    }

    val available: Boolean get() = session != null

    fun estimate(bitmap: Bitmap): DepthMap? {
        val s = session ?: return null
        return try {
            run(s, bitmap, keepAspect = true)
        } catch (e: Throwable) {
            // Some model exports only accept a square input.
            try {
                run(s, bitmap, keepAspect = false)
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun run(s: OrtSession, bitmap: Bitmap, keepAspect: Boolean): DepthMap {
        val w: Int
        val h: Int
        if (keepAspect) {
            val scale = INPUT_LONG_SIDE.toFloat() / max(bitmap.width, bitmap.height)
            w = mult14(bitmap.width * scale)
            h = mult14(bitmap.height * scale)
        } else {
            w = 518
            h = 518
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val n = w * h
        val fb = ByteBuffer.allocateDirect(4 * 3 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until n) {
                val v = ((px[i] shr shift) and 0xFF) / 255f
                fb.put((v - mean[c]) / std[c])
            }
        }
        fb.rewind()

        return OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                val oh = shape[shape.size - 2].toInt()
                val ow = shape[shape.size - 1].toInt()
                val buf = out.floatBuffer
                val raw = FloatArray(buf.remaining())
                buf.get(raw)
                DepthMap(ow, oh, smooth(normalize(raw), ow, oh))
            }
        }
    }

    /** Model output is relative inverse depth (bigger = nearer). Stretch to 0..1, ignoring outliers. */
    private fun normalize(raw: FloatArray): FloatArray {
        val sorted = raw.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.98f).toInt()]
        val range = if (hi - lo > 1e-6f) hi - lo else 1f
        return FloatArray(raw.size) { ((raw[it] - lo) / range).coerceIn(0f, 1f) }
    }

    /** Two passes of a 3×3 box blur: soft depth edges avoid tearing when pixels shift. */
    private fun smooth(src: FloatArray, w: Int, h: Int): FloatArray {
        var a = src
        repeat(2) {
            val b = FloatArray(a.size)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sum = 0f
                    var cnt = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w) continue
                            sum += a[yy * w + xx]
                            cnt++
                        }
                    }
                    b[y * w + x] = sum / cnt
                }
            }
            a = b
        }
        return a
    }

    private fun mult14(v: Float): Int = max(14, (v / 14f).roundToInt() * 14)

    override fun close() {
        runCatching { session?.close() }
    }

    companion object {
        const val MODEL_ASSET = "depth.onnx"
        private const val INPUT_LONG_SIDE = 392
    }
}
