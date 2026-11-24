package com.memoryreel.app

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The "3D photo" effect: shifts every pixel by an amount that depends on how near it is, so the
 * camera seems to move sideways and push in while near things (people) move more than the
 * background. Works on plain pixel arrays and runs on all CPU cores.
 *
 * Each on-screen slide uses its own [Slot], which keeps the scaled source pixels and the output
 * bitmap between frames.
 */
object DepthWarper {

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    private val pool = Executors.newFixedThreadPool(threads)

    class Slot {
        internal var sourceKey: Bitmap? = null
        internal var sourceLong = 0
        internal var src = IntArray(0)
        internal var sw = 0
        internal var sh = 0
        internal var out: Bitmap? = null
        internal var outPx = IntArray(0)
    }

    /**
     * @param offX, offY camera shift for the nearest pixels, as a fraction of the photo size
     * @param dolly extra zoom for the nearest pixels (push-in feel)
     * @param outLong long side of the output in pixels
     */
    fun render(
        slot: Slot, photo: Bitmap, depth: DepthMap,
        offX: Float, offY: Float, dolly: Float, outLong: Int,
    ): Bitmap {
        val aspect = photo.width.toFloat() / photo.height
        val ow: Int
        val oh: Int
        if (aspect >= 1f) {
            ow = outLong
            oh = max(2, (outLong / aspect).toInt())
        } else {
            oh = outLong
            ow = max(2, (outLong * aspect).toInt())
        }

        // Scaled copy of the photo slightly larger than the output, read once per slide.
        val srcLong = (outLong * 1.25f).toInt()
        if (slot.sourceKey !== photo || slot.sourceLong != srcLong) {
            val s = srcLong.toFloat() / max(photo.width, photo.height)
            val scaled = if (s < 1f) {
                Bitmap.createScaledBitmap(photo, max(2, (photo.width * s).toInt()), max(2, (photo.height * s).toInt()), true)
            } else photo
            slot.sw = scaled.width
            slot.sh = scaled.height
            if (slot.src.size != slot.sw * slot.sh) slot.src = IntArray(slot.sw * slot.sh)
            scaled.getPixels(slot.src, 0, slot.sw, 0, 0, slot.sw, slot.sh)
            if (scaled !== photo) scaled.recycle()
            slot.sourceKey = photo
            slot.sourceLong = srcLong
        }

        val previous = slot.out
        val out: Bitmap = if (previous != null && previous.width == ow && previous.height == oh) {
            previous
        } else {
            previous?.recycle()
            Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also {
                slot.out = it
                slot.outPx = IntArray(ow * oh)
            }
        }

        val src = slot.src
        val sw = slot.sw
        val sh = slot.sh
        val dst = slot.outPx
        // Base zoom keeps shifted samples inside the photo.
        val base = 1f + 2f * max(kotlin.math.abs(offX), kotlin.math.abs(offY)) + 0.02f

        val latch = CountDownLatch(threads)
        val rowsPer = (oh + threads - 1) / threads
        for (t in 0 until threads) {
            val y0 = t * rowsPer
            val y1 = minOf(oh, y0 + rowsPer)
            pool.execute {
                try {
                    for (y in y0 until y1) {
                        val v = (y + 0.5f) / oh
                        val row = y * ow
                        for (x in 0 until ow) {
                            val u = (x + 0.5f) / ow
                            val d = depth.sample(u, v)
                            val z = base * (1f + dolly * d)
                            val su = 0.5f + (u - 0.5f) / z - offX * d
                            val sv = 0.5f + (v - 0.5f) / z - offY * d
                            dst[row + x] = bilinear(src, sw, sh, su * sw - 0.5f, sv * sh - 0.5f)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        out.setPixels(dst, 0, ow, 0, 0, ow, oh)
        return out
    }

    private fun bilinear(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, (w - 1).toFloat())
        val y = fy.coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = if (x0 + 1 < w) x0 + 1 else x0
        val y1 = if (y0 + 1 < h) y0 + 1 else y0
        val ax = ((x - x0) * 256f).toInt()
        val ay = ((y - y0) * 256f).toInt()
        val c00 = src[y0 * w + x0]
        val c10 = src[y0 * w + x1]
        val c01 = src[y1 * w + x0]
        val c11 = src[y1 * w + x1]
        val r = lerp2((c00 shr 16) and 0xFF, (c10 shr 16) and 0xFF, (c01 shr 16) and 0xFF, (c11 shr 16) and 0xFF, ax, ay)
        val g = lerp2((c00 shr 8) and 0xFF, (c10 shr 8) and 0xFF, (c01 shr 8) and 0xFF, (c11 shr 8) and 0xFF, ax, ay)
        val b = lerp2(c00 and 0xFF, c10 and 0xFF, c01 and 0xFF, c11 and 0xFF, ax, ay)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp2(a: Int, b: Int, c: Int, d: Int, ax: Int, ay: Int): Int {
        val top = a * 256 + (b - a) * ax
        val bottom = c * 256 + (d - c) * ax
        return (top * 256 + (bottom - top) * ay) shr 16
    }
}
