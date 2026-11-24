package com.memoryreel.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Turns any music file the phone can play (MP3, M4A, OGG, WAV…) into AAC audio that is exactly
 * as long as the video: loops short songs, cuts long ones, fades in and out.
 * The result is kept in memory (a 1-minute AAC track is under 2 MB) and written by the muxer.
 */
object AudioTranscoder {

    class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    class Result(val format: MediaFormat, val samples: List<Sample>)

    private const val TIMEOUT_US = 5_000L
    private const val FADE_IN_US = 300_000L
    private const val FADE_OUT_US = 2_000_000L

    fun transcode(context: Context, uri: Uri, durationUs: Long, isCancelled: () -> Boolean): Result? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var enc: MediaCodec? = null

            val info = MediaCodec.BufferInfo()
            val pending = ArrayDeque<ByteArray>()
            var pendingOffset = 0
            var framesDecoded = 0L      // PCM frames kept so far (after cutting)
            var framesEncoded = 0L      // PCM frames handed to the encoder
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderInputDone = false
            var encoderDone = false
            var outFormat: MediaFormat? = null
            val samples = ArrayList<Sample>()
            var readSinceLoop = 0
            var guard = 0

            fun startEncoder(): MediaCodec {
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                    encoder = it
                }
            }

            while (!encoderDone) {
                if (isCancelled()) return null
                if (++guard > 2_000_000) return null

                // 1. Feed compressed music into the decoder (looping at the end of the file).
                if (!decoderInputDone) {
                    val ii = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        var size = extractor.readSampleData(buf, 0)
                        if (size < 0 && readSinceLoop > 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            readSinceLoop = 0
                            size = extractor.readSampleData(buf, 0)
                        }
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                            readSinceLoop++
                        }
                    }
                }

                // 2. Take PCM out of the decoder, cut it to length and apply the fades.
                if (!decoderOutputDone && pending.size < 16) {
                    val oi = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                        ) return null
                    } else if (oi >= 0) {
                        if (channels !in 1..2) return null
                        if (enc == null) enc = startEncoder()
                        val totalFrames = durationUs * sampleRate / 1_000_000L
                        val frameBytes = 2 * channels
                        if (info.size > 0 && framesDecoded < totalFrames) {
                            val buf = dec.getOutputBuffer(oi)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val keepFrames = min((info.size / frameBytes).toLong(), totalFrames - framesDecoded).toInt()
                            val bytes = ByteArray(keepFrames * frameBytes)
                            buf.get(bytes)
                            applyFades(bytes, channels, framesDecoded, sampleRate, durationUs)
                            pending.addLast(bytes)
                            framesDecoded += keepFrames
                        }
                        dec.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || framesDecoded >= totalFrames) {
                            decoderOutputDone = true
                            decoderInputDone = true
                        }
                    }
                }

                // 3. Feed PCM into the AAC encoder.
                val e = enc
                if (e != null && !encoderInputDone && (pending.isNotEmpty() || decoderOutputDone)) {
                    val ei = e.dequeueInputBuffer(TIMEOUT_US)
                    if (ei >= 0) {
                        val pts = framesEncoded * 1_000_000L / sampleRate
                        if (pending.isEmpty()) {
                            e.queueInputBuffer(ei, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        } else {
                            val buf = e.getInputBuffer(ei)!!
                            buf.clear()
                            val chunk = pending.first()
                            val frameBytes = 2 * channels
                            var count = min(buf.remaining(), chunk.size - pendingOffset)
                            count -= count % frameBytes
                            buf.put(chunk, pendingOffset, count)
                            e.queueInputBuffer(ei, 0, count, pts, 0)
                            framesEncoded += count / frameBytes
                            pendingOffset += count
                            if (pendingOffset >= chunk.size) {
                                pending.removeFirst()
                                pendingOffset = 0
                            }
                        }
                    }
                } else if (e == null && decoderOutputDone) {
                    return null // the file produced no audio at all
                }

                // 4. Collect the encoded AAC packets.
                if (e != null) {
                    val eo = e.dequeueOutputBuffer(info, if (encoderInputDone) TIMEOUT_US else 0)
                    if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = e.outputFormat
                    } else if (eo >= 0) {
                        val buf = e.getOutputBuffer(eo)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buf.get(data)
                            samples.add(Sample(data, info.presentationTimeUs, info.flags))
                        }
                        e.releaseOutputBuffer(eo, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                    }
                }
            }
            val format = outFormat ?: return null
            return if (samples.isEmpty()) null else Result(format, samples)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            extractor.release()
        }
    }

    /** 16-bit PCM: gentle fade-in at the start and a 2 s fade-out before the video ends. */
    private fun applyFades(bytes: ByteArray, channels: Int, firstFrame: Long, sampleRate: Int, durationUs: Long) {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes.size / (2 * channels)
        for (f in 0 until frames) {
            val tUs = (firstFrame + f) * 1_000_000L / sampleRate
            val gain = when {
                tUs < FADE_IN_US -> tUs.toFloat() / FADE_IN_US
                tUs > durationUs - FADE_OUT_US -> ((durationUs - tUs).toFloat() / FADE_OUT_US).coerceIn(0f, 1f)
                else -> 1f
            }
            if (gain >= 1f) continue
            for (c in 0 until channels) {
                val idx = (f * channels + c) * 2
                val v = bb.getShort(idx)
                bb.putShort(idx, (v * gain).toInt().toShort())
            }
        }
    }
}
