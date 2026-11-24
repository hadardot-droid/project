package com.memoryreel.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class ReelStyle(val label: String, val transition: Float, val zoom: Float, val defaultSeconds: Float) {
    CALM("רגוע", 1.0f, 0.12f, 3.0f),
    ENERGETIC("אנרגטי", 0.35f, 0.20f, 1.8f),
    VINTAGE("וינטג'", 1.2f, 0.10f, 3.0f),
    DREAMY("חלומי", 1.4f, 0.16f, 3.5f),
}

enum class ReelFormat(val label: String, val width: Int, val height: Int) {
    STORY("סטורי 9:16", 1080, 1920),
    SQUARE("ריבוע 1:1", 1080, 1080),
    WIDE("רחב 16:9", 1920, 1080);

    val ratio: Float get() = width.toFloat() / height
}

data class ReelConfig(
    val photos: List<Uri>,
    val style: ReelStyle,
    val format: ReelFormat,
    val secondsPerPhoto: Float,
    val title: String,
    val subtitle: String,
    val depth3d: Boolean = true,
)

/**
 * Draws one frame of the memory video at time [t] onto any Canvas. The same code feeds the
 * live preview in the app and the encoder during export, so what you see is what you get.
 * All sizes are relative to the canvas, so it works at any resolution.
 */
class SlideshowRenderer(private val store: PhotoStore, val config: ReelConfig) {

    private val n = config.photos.size
    private val per = config.secondsPerPhoto
    private val tr = min(config.style.transition, per * 0.6f)
    val totalSeconds: Float = if (n == 0) 0f else n * per + tr

    fun slideStart(i: Int): Float = i * per
    fun slideAt(t: Float): Int = if (n == 0) 0 else floor(t / per).toInt().coerceIn(0, n - 1)

    private class Motion(val zoomIn: Boolean, val panX: Float, val panY: Float, val rot: Float, val dir: Int)

    // Each photo gets its own stable, random camera move (seeded by the photo, not the frame).
    private val motions = config.photos.mapIndexed { i, uri ->
        val r = Random(uri.hashCode() * 31 + i)
        Motion(
            zoomIn = r.nextBoolean(),
            panX = r.nextFloat() * 2f - 1f,
            panY = r.nextFloat() * 2f - 1f,
            rot = r.nextFloat() * 2f - 1f,
            dir = if (r.nextBoolean()) 1 else -1,
        )
    }

    private val style = config.style
    private val matrix = Matrix()
    private val shaderMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val shaders = HashMap<Bitmap, BitmapShader>()
    private val slots = arrayOf(DepthWarper.Slot(), DepthWarper.Slot())

    private val sepia: ColorMatrixColorFilter? = if (style == ReelStyle.VINTAGE) {
        val desat = ColorMatrix().apply { setSaturation(0.35f) }
        val warm = ColorMatrix(
            floatArrayOf(
                1.08f, 0f, 0f, 0f, 14f,
                0f, 1.0f, 0f, 0f, 6f,
                0f, 0f, 0.86f, 0f, -6f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        desat.postConcat(warm)
        ColorMatrixColorFilter(desat)
    } else null

    private val grainShader: BitmapShader? = if (style == ReelStyle.VINTAGE) {
        val size = 128
        val r = Random(7)
        val px = IntArray(size * size) {
            val v = r.nextInt(256)
            Color.argb(34, v, v, v)
        }
        BitmapShader(
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888),
            Shader.TileMode.REPEAT, Shader.TileMode.REPEAT,
        )
    } else null
    private val grainPaint = Paint().apply { shader = grainShader }

    private val leakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
    private var leakShader: RadialGradient? = null
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sizeW = 0
    private var sizeH = 0

    init {
        val face = when (style) {
            ReelStyle.VINTAGE -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            ReelStyle.ENERGETIC -> Typeface.create("sans-serif", Typeface.BOLD)
            ReelStyle.DREAMY -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            ReelStyle.CALM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        titlePaint.typeface = face
        subPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun ensureSize(w: Int, h: Int) {
        if (w == sizeW && h == sizeH) return
        sizeW = w
        sizeH = h
        val fw = w.toFloat()
        val fh = h.toFloat()
        val strength = when (style) {
            ReelStyle.VINTAGE -> 170
            ReelStyle.DREAMY -> 90
            ReelStyle.CALM -> 70
            ReelStyle.ENERGETIC -> 60
        }
        vignettePaint.shader = RadialGradient(
            fw / 2f, fh / 2f, hypot(fw, fh) / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(strength, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        if (style == ReelStyle.DREAMY) {
            leakShader = RadialGradient(
                0f, 0f, max(fw, fh) * 0.55f,
                intArrayOf(Color.argb(95, 255, 176, 120), Color.argb(45, 255, 110, 170), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            leakPaint.shader = leakShader
        }
    }

    /**
     * @param blocking true while exporting (wait for full-size photos), false for the live
     * preview (draw the thumbnail until the full photo has loaded).
     */
    fun draw(canvas: Canvas, w: Int, h: Int, t: Float, blocking: Boolean) {
        ensureSize(w, h)
        canvas.drawColor(Color.BLACK)
        if (n == 0 || w <= 0 || h <= 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()

        val cur = slideAt(t)
        for (i in max(0, cur - 1)..cur) {
            val start = i * per
            val end = start + per + tr
            if (t < start || (t >= end && i != n - 1)) continue
            val local = ((t - start) / (per + tr)).coerceIn(0f, 1f)
            val inP = if (i == 0) 1f else ((t - start) / tr).coerceIn(0f, 1f)
            val outP = if (i == n - 1) 0f else ((t - start - per) / tr).coerceIn(0f, 1f)
            drawSlide(canvas, i, local, inP, outP, fw, fh, blocking)
        }

        drawStyleEffects(canvas, fw, fh, t)
        drawTitle(canvas, fw, fh, t)

        // Fade in from black at the start, fade out at the end.
        val fade = when {
            t < 0.5f -> 1f - t / 0.5f
            t > totalSeconds - 0.8f -> ((t - (totalSeconds - 0.8f)) / 0.8f).coerceIn(0f, 1f)
            else -> 0f
        }
        if (fade > 0f) {
            fillPaint.color = Color.BLACK
            fillPaint.alpha = (fade * 255).toInt()
            canvas.drawRect(0f, 0f, fw, fh, fillPaint)
        }
    }

    private fun drawSlide(
        canvas: Canvas, i: Int, local: Float, inP: Float, outP: Float,
        fw: Float, fh: Float, blocking: Boolean,
    ) {
        val uri = config.photos[i]
        val m = motions[i]
        val original = (if (blocking) store.getBlocking(uri) else store.get(uri)) ?: store.thumb(uri) ?: return
        if (!blocking && i + 1 < n) store.prefetch(config.photos[i + 1])

        canvas.save()
        var alpha = 255
        if (style == ReelStyle.ENERGETIC) {
            // Push transition: the new photo slides in and shoves the old one out.
            val inDir = m.dir
            val outDir = if (i + 1 < n) motions[i + 1].dir else 1
            canvas.translate((1f - smooth(inP)) * fw * inDir - smooth(outP) * fw * outDir, 0f)
        } else {
            alpha = (smooth(inP) * 255).toInt()
        }
        if (alpha <= 0) {
            canvas.restore()
            return
        }

        val e = if (style == ReelStyle.ENERGETIC) 1f - (1f - local) * (1f - local) * (1f - local) else local
        val sweep = e * 2f - 1f

        // 3D depth effect: re-render the photo with near pixels moving more than far ones.
        val depth = if (config.depth3d) store.depth(uri) else null
        var panScale = 1f
        val photo = if (depth != null) {
            val shift = if (style == ReelStyle.ENERGETIC) 0.05f else 0.035f
            val frameLong = max(fw, fh).toInt()
            val outLong = if (blocking) frameLong.coerceAtMost(1920) else frameLong.coerceAtMost(640)
            panScale = 0.35f
            DepthWarper.render(
                slots[i % 2], original, depth,
                offX = m.panX * shift * sweep,
                offY = m.panY * shift * 0.5f * sweep,
                dolly = 0.06f * e,
                outLong = outLong,
            )
        } else original

        val pw = photo.width.toFloat()
        val ph = photo.height.toFloat()
        val cover = abs(ln((pw / ph) / (fw / fh))) < ln(1.35f)
        val z = if (m.zoomIn) 1f + style.zoom * e else 1f + style.zoom * (1f - e)

        bmpPaint.alpha = alpha
        bmpPaint.colorFilter = sepia

        if (cover) {
            // Photo shape is close to the frame: fill it and do a slow Ken Burns move.
            val s = max(fw / pw, fh / ph) * z
            val slackX = (pw * s - fw) / 2f
            val slackY = (ph * s - fh) / 2f
            matrix.reset()
            matrix.postTranslate(-pw / 2f, -ph / 2f)
            matrix.postScale(s, s)
            matrix.postTranslate(
                fw / 2f + m.panX * slackX * sweep * 0.9f * panScale,
                fh / 2f + m.panY * slackY * sweep * 0.9f * panScale,
            )
            canvas.drawBitmap(photo, matrix, bmpPaint)
        } else {
            // Different shape: blurred copy behind, the photo floats on top as a card (parallax).
            val blur = store.blur(uri) ?: photo
            val bw = blur.width.toFloat()
            val bh = blur.height.toFloat()
            val bs = max(fw / bw, fh / bh) * (1.25f + 0.1f * e)
            matrix.reset()
            matrix.postTranslate(-bw / 2f, -bh / 2f)
            matrix.postScale(bs, bs)
            matrix.postTranslate(fw / 2f - m.panX * fw * 0.05f * sweep, fh / 2f - m.panY * fh * 0.05f * sweep)
            canvas.drawBitmap(blur, matrix, bmpPaint)
            fillPaint.color = Color.BLACK
            fillPaint.alpha = (alpha * 0.35f).toInt()
            canvas.drawRect(0f, 0f, fw, fh, fillPaint)

            val margin = if (style == ReelStyle.VINTAGE) 0.80f else 0.86f
            val cardZoom = 1f + 0.06f * (if (m.zoomIn) e else 1f - e)
            val s = min(fw / pw, fh / ph) * margin * cardZoom
            val cx = fw / 2f + m.panX * fw * 0.02f * sweep
            val cy = fh / 2f + m.panY * fh * 0.02f * sweep
            val rot = when (style) {
                ReelStyle.VINTAGE -> m.rot * 3f
                ReelStyle.DREAMY -> m.rot * 1.5f * sweep
                ReelStyle.ENERGETIC -> m.rot * 2f * (1f - e)
                ReelStyle.CALM -> 0f
            }
            val hw = pw / 2f
            val hh = ph / 2f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rot)
            canvas.scale(s, s)
            if (style == ReelStyle.VINTAGE) {
                // White print border, thicker at the bottom like an instant photo.
                val b = fw * 0.022f / s
                framePaint.color = Color.argb(alpha, 247, 241, 227)
                framePaint.setShadowLayer(fw * 0.03f / s, 0f, fw * 0.01f / s, Color.argb((alpha * 0.6f).toInt(), 0, 0, 0))
                canvas.drawRect(-hw - b, -hh - b, hw + b, hh + b * 3.2f, framePaint)
            } else {
                shadowPaint.color = Color.argb(alpha, 0, 0, 0)
                shadowPaint.setShadowLayer(fw * 0.04f / s, 0f, fw * 0.015f / s, Color.argb((alpha * 0.7f).toInt(), 0, 0, 0))
                val r = fw * 0.025f / s
                canvas.drawRoundRect(-hw, -hh, hw, hh, r, r, shadowPaint)
            }
            val radius = if (style == ReelStyle.VINTAGE) 0f else fw * 0.025f / s
            shaderMatrix.setTranslate(-hw, -hh)
            val shader = shaderFor(photo)
            shader.setLocalMatrix(shaderMatrix)
            shaderPaint.shader = shader
            shaderPaint.alpha = alpha
            shaderPaint.colorFilter = sepia
            canvas.drawRoundRect(-hw, -hh, hw, hh, radius, radius, shaderPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun shaderFor(b: Bitmap): BitmapShader {
        shaders[b]?.let { return it }
        if (shaders.size > 4) shaders.clear()
        return BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shaders[b] = it }
    }

    private fun drawStyleEffects(canvas: Canvas, fw: Float, fh: Float, t: Float) {
        when (style) {
            ReelStyle.VINTAGE -> {
                canvas.drawRect(0f, 0f, fw, fh, vignettePaint)
                val frame = (t * 24f).toInt()
                val r = Random(frame)
                val gs = max(1f, fw / 540f)
                shaderMatrix.reset()
                shaderMatrix.setScale(gs, gs)
                shaderMatrix.postTranslate(r.nextFloat() * 128f * gs, r.nextFloat() * 128f * gs)
                grainShader?.setLocalMatrix(shaderMatrix)
                canvas.drawRect(0f, 0f, fw, fh, grainPaint)
            }
            ReelStyle.DREAMY -> {
                // A warm light leak drifting slowly across the frame.
                val cx = fw * (0.5f + 0.45f * sin(t * 0.35f))
                val cy = fh * (0.3f + 0.25f * cos(t * 0.27f))
                shaderMatrix.setTranslate(cx, cy)
                leakShader?.setLocalMatrix(shaderMatrix)
                canvas.drawRect(0f, 0f, fw, fh, leakPaint)
                canvas.drawRect(0f, 0f, fw, fh, vignettePaint)
            }
            ReelStyle.CALM -> canvas.drawRect(0f, 0f, fw, fh, vignettePaint)
            ReelStyle.ENERGETIC -> {
                // Quick white flash on every cut, like a beat.
                val k = floor(t / per).toInt()
                val since = t - k * per
                if (k in 1 until n && since < 0.15f) {
                    fillPaint.color = Color.WHITE
                    fillPaint.alpha = ((1f - since / 0.15f) * 110).toInt()
                    canvas.drawRect(0f, 0f, fw, fh, fillPaint)
                }
                canvas.drawRect(0f, 0f, fw, fh, vignettePaint)
            }
        }
    }

    private fun drawTitle(canvas: Canvas, fw: Float, fh: Float, t: Float) {
        val title = config.title.trim()
        val sub = config.subtitle.trim()
        if (title.isEmpty() && sub.isEmpty()) return
        val showEnd = min(3.6f, totalSeconds - 0.5f)
        val a = when {
            t < 0.4f || t > showEnd -> 0f
            t < 1.0f -> (t - 0.4f) / 0.6f
            t > showEnd - 0.6f -> (showEnd - t) / 0.6f
            else -> 1f
        }.coerceIn(0f, 1f)
        if (a <= 0f) return

        val unit = min(fw, fh)
        val size = unit * 0.085f
        titlePaint.textSize = size
        titlePaint.alpha = (a * 255).toInt()
        titlePaint.setShadowLayer(unit * 0.02f, 0f, unit * 0.005f, Color.argb((a * 170).toInt(), 0, 0, 0))
        subPaint.textSize = size * 0.42f
        subPaint.alpha = (a * 230).toInt()
        subPaint.setShadowLayer(unit * 0.015f, 0f, unit * 0.004f, Color.argb((a * 170).toInt(), 0, 0, 0))

        val baseY = fh * (if (fh > fw) 0.80f else 0.76f) + (1f - smooth(a)) * fh * 0.02f
        val maxW = fw * 0.88f
        if (title.isNotEmpty()) {
            val text = TextUtils.ellipsize(title, titlePaint, maxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, fw / 2f, baseY, titlePaint)
        }
        if (sub.isNotEmpty()) {
            val text = TextUtils.ellipsize(sub, subPaint, maxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, fw / 2f, baseY + size * 0.8f, subPaint)
        }
    }

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
}
