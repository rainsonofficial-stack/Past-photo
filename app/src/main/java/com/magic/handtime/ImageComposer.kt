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

        val typefaces = loadTypefaceVariations(context)

        val baseColor = try { Color.parseColor(textColorHex) } catch (e: Exception) { Color.BLACK }
        val opacityFraction = (opacityPct.coerceIn(0, 100)) / 100f
        val alpha = (opacityFraction * 255).toInt()
        val colorWithAlpha = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaces.first()
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
        drawHandwrittenText(textCanvas, finalLayout, paint, displayText, baseColor, opacityFraction, typefaces)
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
            else -> { }
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
        fillOpacity: Float,
        typefaces: List<Typeface>
    ) {
        val random = Random(System.nanoTime())

        // Per-CHARACTER rotation: each specific letter (A, B, C...) tracks its
        // own independent counter through the 6 font variations. The 2nd
        // occurrence of "a" anywhere in the text uses font 2, regardless of
        // how many other different letters appeared between the two "a"s.
        val perLetterCycleIndex = mutableMapOf<Char, Int>()

        for (lineIndex in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineBaseline = layout.getLineBaseline(lineIndex).toFloat()
            val lineLeft = layout.getLineLeft(lineIndex)
            val lineRight = layout.getLineRight(lineIndex)
            val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)

            // Whole-line wobble, restored — a gentle sine-wave drift across
            // the line's baseline, on top of the per-letter jitter below.
            val driftAmplitude = basePaint.textSize * (0.03f + random.nextFloat() * 0.05f)
            val driftPhase = random.nextFloat() * (2 * Math.PI).toFloat()
            val driftFrequency = 1.5f + random.nextFloat() * 1.5f

            var cursorX = lineLeft

            for (i in lineStart until lineEnd) {
                val ch = fullText[i]
                if (ch == '\n') continue

                val charPaint = TextPaint(basePaint)
                charPaint.style = Paint.Style.FILL

                if (ch.isLetter()) {
                    val key = ch.lowercaseChar()
                    val idx = perLetterCycleIndex.getOrDefault(key, 0)
                    charPaint.typeface = typefaces[idx % typefaces.size]
                    perLetterCycleIndex[key] = idx + 1
                } else {
                    charPaint.typeface = typefaces.first()
                }

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

                val skewJitter = (random.nextFloat() * 0.2f) - 0.1f
                charPaint.textSkewX = skewJitter

                val charWidth = charPaint.measureText(ch.toString())

                canvas.save()
                canvas.translate(cursorX, lineBaseline + perCharBaselineJitter + lineDrift)
                canvas.drawText(ch.toString(), 0f, 0f, charPaint)
                canvas.restore()

                cursorX += charWidth
            }
        }
    }

    private fun loadTypefaceVariations(context: Context): List<Typeface> {
        return try {
            val assetManager = context.assets
            val fontFiles = (assetManager.list("fonts") ?: emptyArray())
                .filter { it.endsWith(".ttf", ignoreCase = true) || it.endsWith(".otf", ignoreCase = true) }
                .sorted()

            if (fontFiles.isEmpty()) {
                lastFontDebugInfo = "No font files found in assets/fonts/"
                return listOf(Typeface.DEFAULT)
            }

            val loaded = fontFiles.mapNotNull { file ->
                try {
                    Typeface.createFromAsset(assetManager, "fonts/$file")
                } catch (e: Exception) {
                    null
                }
            }

            if (loaded.isEmpty()) {
                lastFontDebugInfo = "Found font files but none loaded successfully"
                listOf(Typeface.DEFAULT)
            } else {
                lastFontDebugInfo = "Loaded ${loaded.size} variation(s): ${fontFiles.joinToString()}"
                loaded
            }
        } catch (e: Exception) {
            lastFontDebugInfo = "Font load FAILED: ${e.javaClass.simpleName} - ${e.message}"
            listOf(Typeface.DEFAULT)
        }
    }
}
