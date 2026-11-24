package com.memoryreel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.ceil

/**
 * Renders every frame with [SlideshowRenderer] into a bitmap, converts it to YUV and feeds the
 * H.264 encoder with exact timestamps, then muxes the video with the (optional) music into MP4.
 * Uses only Android's built-in MediaCodec/MediaMuxer: no extra libraries, works offline.
 */
class VideoExporter(private val context: Context) {

    class ExportException(message: String) : Exception(message)

    suspend fun export(
        store: PhotoStore,
        config: ReelConfig,
        scale: Float,
        musicUri: Uri?,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val ctx = currentCoroutineContext()
        val renderer = SlideshowRenderer(store, config)
        val fps = 30
        val totalUs = (renderer.totalSeconds * 1_000_000L).toLong()
        val totalFrames = ceil(renderer.totalSeconds * fps).toInt()

        // 1. Music first (fast), kept in memory.
        val audio = musicUri?.let { uri ->
            onProgress(0.01f)
            AudioTranscoder.transcode(context, uri, totalUs) { !ctx.isActive }
                ?: throw ExportException("לא הצלחתי לקרוא את קובץ המוזיקה. נסו קובץ MP3 או M4A אחר.")
        }
        ctx.ensureActive()

        // 2. Pick a size the phone's encoder accepts (even numbers, falls back to smaller).
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        var w = even(config.format.width * scale)
        var h = even(config.format.height * scale)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var format: MediaFormat? = null
        var codecName: String? = null
        for (attempt in 0 until 3) {
            val f = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps / 7).toInt().coerceIn(2_000_000, 16_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val name = codecList.findEncoderForFormat(f)
            if (name != null) {
                format = f
                codecName = name
                break
            }
            w = even(w * 0.75f)
            h = even(h * 0.75f)
        }
        if (format == null || codecName == null) throw ExportException("הטלפון לא תומך בקידוד וידאו בגודל הזה.")

        val encoder = MediaCodec.createByCodecName(codecName)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrack = -1
        var audioTrack = -1
        var audioIndex = 0
        val info = MediaCodec.BufferInfo()

        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val argb = IntArray(w * h)
        val yRow = ByteArray(w)

        fun writePendingAudio(upToUs: Long) {
            val a = audio ?: return
            if (!muxerStarted) return
            val ai = MediaCodec.BufferInfo()
            while (audioIndex < a.samples.size && a.samples[audioIndex].ptsUs <= upToUs) {
                val s = a.samples[audioIndex++]
                ai.set(0, s.data.size, s.ptsUs, s.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                muxer.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(s.data), ai)
            }
        }

        fun drain(endOfStream: Boolean) {
            var spins = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++spins > 500) throw ExportException("המקודד הפסיק להגיב.")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw ExportException("פורמט הווידאו השתנה באמצע.")
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                            writePendingAudio(info.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        var ok = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            for (i in 0 until totalFrames) {
                ctx.ensureActive()
                val t = i.toFloat() / fps
                renderer.draw(canvas, w, h, t, blocking = true)
                frame.getPixels(argb, 0, w, 0, 0, w, h)

                var inIdx: Int
                while (true) {
                    inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) break
                    drain(false)
                    ctx.ensureActive()
                }
                val image = encoder.getInputImage(inIdx) ?: throw ExportException("המקודד לא מספק תמונת קלט.")
                fillYuv(image, argb, w, h, yRow)
                val ptsUs = i * 1_000_000L / fps
                encoder.queueInputBuffer(inIdx, 0, w * h * 3 / 2, ptsUs, 0)
                drain(false)
                onProgress(0.03f + 0.95f * (i + 1) / totalFrames)
            }

            var eosIdx: Int
            do {
                eosIdx = encoder.dequeueInputBuffer(10_000)
                if (eosIdx < 0) drain(false)
            } while (eosIdx < 0)
            encoder.queueInputBuffer(eosIdx, 0, 0, totalFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            writePendingAudio(Long.MAX_VALUE)
            ok = true
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            frame.recycle()
            if (!ok) outFile.delete()
        }
        onProgress(1f)
    }

    private fun even(v: Float): Int = (v.toInt() / 2) * 2
    private fun even(v: Int): Int = (v / 2) * 2

    /** ARGB → YUV 4:2:0 (BT.601), written into whatever plane layout the encoder gives us. */
    private fun fillYuv(image: Image, argb: IntArray, w: Int, h: Int, yRow: ByteArray) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val yStride = planes[0].rowStride
        val yPix = planes[0].pixelStride
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                val c = argb[off + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (yPix == 1) {
                yBuf.position(y * yStride)
                yBuf.put(yRow, 0, w)
            } else {
                for (x in 0 until w) yBuf.put(y * yStride + x * yPix, yRow[x])
            }
        }

        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val uStride = planes[1].rowStride
        val vStride = planes[2].rowStride
        val uPix = planes[1].pixelStride
        val vPix = planes[2].pixelStride
        for (y in 0 until h / 2) {
            val r0 = (y * 2) * w
            val r1 = r0 + w
            for (x in 0 until w / 2) {
                val x0 = x * 2
                val c0 = argb[r0 + x0]
                val c1 = argb[r0 + x0 + 1]
                val c2 = argb[r1 + x0]
                val c3 = argb[r1 + x0 + 1]
                val r = (((c0 shr 16) and 0xFF) + ((c1 shr 16) and 0xFF) + ((c2 shr 16) and 0xFF) + ((c3 shr 16) and 0xFF)) shr 2
                val g = (((c0 shr 8) and 0xFF) + ((c1 shr 8) and 0xFF) + ((c2 shr 8) and 0xFF) + ((c3 shr 8) and 0xFF)) shr 2
                val b = ((c0 and 0xFF) + (c1 and 0xFF) + (c2 and 0xFF) + (c3 and 0xFF)) shr 2
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(y * uStride + x * uPix, u.toByte())
                vBuf.put(y * vStride + x * vPix, v.toByte())
            }
        }
    }
}
