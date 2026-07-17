package com.magic.handtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import kotlin.math.sin
import kotlin.random.Random

object ImageComposer {

    var lastFontDebugInfo: String = ""

    fun composeImage(
        context: Context,
        baseImagePath: String,
        text: String,
        marginLeftPct: Int,
        marginRightPct: Int,
        marginTopPct: Int,
        marginBottomPct: Int,
        textColorHex: String,
        opacityPct: Int,
        rotationDeg: Float = 0f
    ): Bitmap {
        val original = loadAndCorrectOrientation(baseImagePath)
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val boxLeft = w * (marginLeftPct / 100f)
        val boxRight = w * (1f - marginRightPct / 100f)
        val boxTop = h * (marginTopPct / 100f)
        val boxBottom = h * (1f - marginBottomPct / 100f)
        val boxWidth = (boxRight - boxLeft).coerceAtLeast(10f)
        val boxHeight = (boxBottom - boxTop).coerceAtLeast(10f)

        val typeface = loadHandwritingTypeface(context)

        val baseColor = try { Color.parseColor(textColorHex) } catch (e: Exception) { Color.BLACK }
        val opacityFraction = (opacityPct.coerceIn(0, 100)) / 100f
        val alpha = (opacityFraction * 255).toInt()
        val colorWithAlpha = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.color = colorWithAlpha

        val displayText = buildDisplayText(text)

        var lo = 8f
        var hi = 400f
        var bestLayout: StaticLayout? = null

        while (lo <= hi) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            val layout = buildLayout(displayText, paint, boxWidth.toInt())
            if (layout.height <= boxHeight) {
                bestLayout = layout
                lo = mid + 1f
            } else {
                hi = mid - 1f
            }
        }

        val finalLayout = bestLayout ?: buildLayout(displayText, paint.apply { textSize = lo.coerceAtLeast(8f) }, boxWidth.toInt())

        val textX = boxLeft + (boxWidth - finalLayout.width) / 2f
        val textY = boxTop + (boxHeight - finalLayout.height) / 2f
        val centerX = textX + finalLayout.width / 2f
        val centerY = textY + finalLayout.height / 2f

        val textLayer = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val textCanvas = Canvas(textLayer)
        textCanvas.save()
        textCanvas.rotate(rotationDeg, centerX, centerY)
        textCanvas.translate(textX, textY)
        drawHandwrittenText(textCanvas, finalLayout, paint, displayText, baseColor, opacityFraction)
        textCanvas.restore()

        val softenedTextLayer = softenLayer(textLayer, strength = 0.6f)

        canvas.drawBitmap(softenedTextLayer, 0f, 0f, null)

        return bitmap
    }

    private fun softenLayer(source: Bitmap, strength: Float): Bitmap {
        val scale = (1f - strength.coerceIn(0f, 0.6f))
        val smallW = (source.width * scale).toInt().coerceAtLeast(1)
        val smallH = (source.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val restored = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        return restored
    }

    private fun loadAndCorrectOrientation(path: String): Bitmap {
        val original = BitmapFactory.decodeFile(path)
            ?: throw IllegalStateException("Base image not found")

        val orientation = try {
            val exif = ExifInterface(path)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> { /* no correction needed */ }
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } else {
            original
        }
    }

    private fun buildDisplayText(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2) return text.trim()

        val lines = StringBuilder()
        var i = 0
        while (i < words.size) {
            val chunk = if (i + 1 < words.size) "${words[i]} ${words[i + 1]}" else words[i]
            lines.append(chunk)
            i += 2
            if (i < words.size) lines.append("\n")
        }
        return lines.toString()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.85f)
            .setIncludePad(false)
            .build()
    }

    private fun drawHandwrittenText(
        canvas: Canvas,
        layout: StaticLayout,
        basePaint: TextPaint,
        fullText: String,
        baseColor: Int,
        fillOpacity: Float
    ) {
        val random = Random(System.nanoTime())

        for (lineIndex in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineBaseline = layout.getLineBaseline(lineIndex).toFloat()
            val lineLeft = layout.getLineLeft(lineIndex)
            val lineRight = layout.getLineRight(lineIndex)
            val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)

            val driftAmplitude = basePaint.textSize * (0.03f + random.nextFloat() * 0.05f)
            val driftPhase = random.nextFloat() * (2 * Math.PI).toFloat()
            val driftFrequency = 1.5f + random.nextFloat() * 1.5f

            var cursorX = lineLeft

            for (i in lineStart until lineEnd) {
                val ch = fullText[i]
                if (ch == '\n') continue

                val charPaint = TextPaint(basePaint)
                charPaint.style = Paint.Style.FILL

                val shade = 0.85f + random.nextFloat() * 0.3f
                val jitteredColor = Color.rgb(
                    (Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255)
                )
                charPaint.color = Color.argb((fillOpacity * 255).toInt(), Color.red(jitteredColor), Color.green(jitteredColor), Color.blue(jitteredColor))

                val sizeJitter = basePaint.textSize * (0.94f + random.nextFloat() * 0.12f)
                charPaint.textSize = sizeJitter

                val perCharBaselineJitter = (random.nextFloat() * basePaint.textSize * 0.06f) - (basePaint.textSize * 0.03f)

                val progress = ((cursorX - lineLeft) / lineWidth).coerceIn(0f, 1f)
                val lineDrift = driftAmplitude * sin(driftPhase + progress * driftFrequency * 2 * Math.PI.toFloat())

                val rotJitter = (random.nextFloat() * 8f) - 4f

                val skewJitter = (random.nextFloat() * 0.34f) - 0.17f
                charPaint.textSkewX = skewJitter

                val charWidth = charPaint.measureText(ch.toString())

                canvas.save()
                canvas.translate(cursorX, lineBaseline + perCharBaselineJitter + lineDrift)
                canvas.rotate(rotJitter, charWidth / 2f, -basePaint.textSize / 3f)

                // Single fill pass only — no stroke/bold overlay, since it was
                // creating a visible outline/cutout look on rounded letters
                // like "B" once blur was applied.
                canvas.drawText(ch.toString(), 0f, 0f, charPaint)

                canvas.restore()

                cursorX += charWidth
            }
        }
    }

    private fun loadHandwritingTypeface(context: Context): Typeface {
        return try {
            val assetManager = context.assets
            val fontFiles = assetManager.list("fonts") ?: emptyArray()

            if (fontFiles.isEmpty()) {
                lastFontDebugInfo = "No files found in assets/fonts/"
                return Typeface.DEFAULT
            }

            val fontFile = fontFiles.firstOrNull {
                it.endsWith(".ttf", ignoreCase = true) || it.endsWith(".otf", ignoreCase = true)
            }

            if (fontFile == null) {
                lastFontDebugInfo = "No .ttf/.otf found: ${fontFiles.joinToString()}"
                return Typeface.DEFAULT
            }

            lastFontDebugInfo = "Loaded $fontFile"
            Typeface.createFromAsset(assetManager, "fonts/$fontFile")
        } catch (e: Exception) {
            lastFontDebugInfo = "Font load FAILED: ${e.javaClass.simpleName} - ${e.message}"
            Typeface.DEFAULT
        }
    }
}
