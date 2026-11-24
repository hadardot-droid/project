package com.memoryreel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Decodes the user's photos on demand and keeps only a few full-size ones in memory.
 * Every photo also has a small thumbnail (for the strip and as a placeholder) and a tiny
 * copy that, stretched to fill the frame, gives the soft blurred background.
 */
class PhotoStore(private val context: Context, private val maxSide: Int) {

    private val cache = object : LruCache<Uri, Bitmap>(140 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
    }
    private val thumbs = ConcurrentHashMap<Uri, Bitmap>()
    private val blurs = ConcurrentHashMap<Uri, Bitmap>()
    private val depths = ConcurrentHashMap<Uri, DepthMap>()
    private val loading = Collections.synchronizedSet(HashSet<Uri>())
    private val executor = Executors.newSingleThreadExecutor()

    fun thumb(uri: Uri): Bitmap? = thumbs[uri]
    fun blur(uri: Uri): Bitmap? = blurs[uri]
    fun depth(uri: Uri): DepthMap? = depths[uri]
    fun hasDepth(uri: Uri): Boolean = depths.containsKey(uri)

    /** Blocking: runs the depth model on a medium-size copy of the photo. */
    fun computeDepth(uri: Uri, estimator: DepthEstimator): Boolean {
        if (depths.containsKey(uri)) return true
        val b = decode(uri, 448) ?: return false
        val d = estimator.estimate(b)
        b.recycle()
        if (d != null) depths[uri] = d
        return d != null
    }

    /** Width / height of the photo, known once its thumbnail is loaded. */
    fun aspect(uri: Uri): Float {
        val t = thumbs[uri] ?: return 1f
        return t.width.toFloat() / t.height.toFloat()
    }

    /** Blocking: loads thumbnail and blur copy. Call from a background thread. */
    fun loadThumb(uri: Uri): Boolean {
        if (thumbs.containsKey(uri)) return true
        val raw = decode(uri, THUMB_SIDE) ?: return false
        val s = THUMB_SIDE.toFloat() / max(raw.width, raw.height)
        val t = if (s < 1f) {
            Bitmap.createScaledBitmap(raw, max(1, (raw.width * s).toInt()), max(1, (raw.height * s).toInt()), true)
                .also { if (it !== raw) raw.recycle() }
        } else raw
        thumbs[uri] = t
        val bw = 24
        val bh = max(1, (bw * t.height.toFloat() / t.width).toInt())
        blurs[uri] = Bitmap.createScaledBitmap(t, bw, bh, true)
        return true
    }

    /** Non-blocking: returns the full photo if it is ready, otherwise starts loading it. */
    fun get(uri: Uri): Bitmap? {
        cache.get(uri)?.let { return it }
        prefetch(uri)
        return null
    }

    /** Blocking: used while exporting, where every frame must be exact. */
    fun getBlocking(uri: Uri): Bitmap? {
        cache.get(uri)?.let { return it }
        val b = decode(uri, maxSide) ?: return thumbs[uri]
        cache.put(uri, b)
        return b
    }

    fun prefetch(uri: Uri) {
        if (cache.get(uri) != null || !loading.add(uri)) return
        executor.execute {
            try {
                if (cache.get(uri) == null) decode(uri, maxSide)?.let { cache.put(uri, it) }
            } finally {
                loading.remove(uri)
            }
        }
    }

    fun release() {
        executor.shutdownNow()
        cache.evictAll()
    }

    private fun decode(uri: Uri, side: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // ImageDecoder applies the EXIF rotation itself and reads HEIC/HEIF too.
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= side) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        cache.evictAll()
        null
    }

    companion object {
        const val THUMB_SIDE = 320
    }
}
