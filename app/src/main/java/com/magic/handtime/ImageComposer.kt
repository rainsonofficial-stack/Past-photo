package com.magic.handtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import kotlin.math.sin
import kotlin.random.Random

object ImageComposer {

    var lastFontDebugInfo: String = ""

    // Per-font thickness control (index = alphabetical font file order,
    // variation1.ttf = 0, variation2.ttf = 1, etc). Extra stroke width as a
    // fraction of text size. 0f = no added thickness.
    private val FONT_THICKNESS = listOf(
        0.00f, // variation1
        0.015f, // variation2
        0.00f, // variation3
        0.030f, // variation4 (+20% from 0.025f baseline)
        0.033f, // variation5 (+30%)
        0.033f  // variation6 (+30%)
    )

    // Per-font SIZE multiplier, same index convention. 1.0f = unchanged,
    // >1.0f = bigger, <1.0f = smaller. Applied on top of the normal per-letter
    // jitter and included in width measurement, so wrapping/shrink-to-fit
    // still accounts for it correctly — no overflow risk.
    private val FONT_SIZE_MULTIPLIER = listOf(
        1.20f, // variation1: +20%
        0.75f, // variation2: -25%
        0.90f, // variation3: -10%
        1.00f, // variation4: unchanged
        1.20f, // variation5: +20%
        2.00f  // variation6: +100%
    )

    private data class CharSpec(
        val ch: Char,
        val typeface: Typeface,
        val typefaceIndex: Int,
        val sizeFactor: Float,
        val skew: Float,
        val baselineJitter: Float,
        val colorOverride: Int
    )

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

        val random = Random(System.nanoTime())

        val words = buildWordCharSpecs(text, typefaces, baseColor, alpha, random)

        val (finalLines, finalTextSize) = fitTextToBox(words, boxWidth, boxHeight, typefaces.first())

        val metricsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        metricsPaint.typeface = typefaces.first()
        metricsPaint.textSize = finalTextSize
        val fm = metricsPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.05f

        val totalTextHeight = lineHeight * finalLines.size
        val textStartY = boxTop + (boxHeight - totalTextHeight) / 2f - fm.ascent

        val centerX = (boxLeft + boxRight) / 2f
        val centerY = (boxTop + boxBottom) / 2f

        val textLayer = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val textCanvas = Canvas(textLayer)
        textCanvas.save()
        textCanvas.rotate(rotationDeg, centerX, centerY)

        drawWrappedText(textCanvas, finalLines, finalTextSize, boxLeft, boxWidth, textStartY, lineHeight, random)

        textCanvas.restore()

        val softenedTextLayer = softenLayer(textLayer, strength = 0.6f)
        canvas.drawBitmap(softenedTextLayer, 0f, 0f, null)

        return bitmap
    }

    private fun thicknessFor(index: Int): Float {
        return if (index < FONT_THICKNESS.size) FONT_THICKNESS[index] else 0f
    }

    private fun sizeMultiplierFor(index: Int): Float {
        return if (index < FONT_SIZE_MULTIPLIER.size) FONT_SIZE_MULTIPLIER[index] else 1f
    }

    private fun buildWordCharSpecs(
        text: String,
        typefaces: List<Typeface>,
        baseColor: Int,
        alpha: Int,
        random: Random
    ): List<List<CharSpec>> {
        val perLetterCycleIndex = mutableMapOf<Char, Int>()
        val rawWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        return rawWords.map { word ->
            word.map { ch ->
                val typeface: Typeface
                val typefaceIndex: Int
                val skew: Float
                if (ch.isLetter()) {
                    val key = ch.lowercaseChar()
                    val idx = perLetterCycleIndex.getOrDefault(key, 0)
                    typefaceIndex = idx % typefaces.size
                    typeface = typefaces[typefaceIndex]
                    perLetterCycleIndex[key] = idx + 1
                    skew = (random.nextFloat() * 0.2f) - 0.1f
                } else {
                    typefaceIndex = 0
                    typeface = typefaces.first()
                    skew = 0f
                }

                // Small natural jitter (±3%) combined with this font's
                // deliberate size multiplier — both included here so wrapping
                // and shrink-to-fit measurements already account for it.
                val jitter = 0.96f + random.nextFloat() * 0.06f
                val sizeFactor = jitter * sizeMultiplierFor(typefaceIndex)

                val baselineJitter = random.nextFloat()
                val shade = 0.85f + random.nextFloat() * 0.3f
                val jitteredColor = Color.rgb(
                    (Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255)
                )
                val colorWithAlpha = Color.argb(alpha, Color.red(jitteredColor), Color.green(jitteredColor), Color.blue(jitteredColor))

                CharSpec(ch, typeface, typefaceIndex, sizeFactor, skew, baselineJitter, colorWithAlpha)
            }
        }
    }

    private fun measureChar(spec: CharSpec, trialSize: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = spec.typeface
        p.textSize = trialSize * spec.sizeFactor
        p.textSkewX = spec.skew
        return p.measureText(spec.ch.toString())
    }

    private fun measureSpace(typeface: Typeface, trialSize: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = typeface
        p.textSize = trialSize
        return p.measureText(" ")
    }

    private fun wrapAtSize(
        words: List<List<CharSpec>>,
        trialSize: Float,
        boxWidth: Float,
        referenceTypeface: Typeface
    ): List<List<CharSpec>> {
        val lines = mutableListOf<MutableList<CharSpec>>()
        var currentLine = mutableListOf<CharSpec>()
        var currentWidth = 0f
        val spaceWidth = measureSpace(referenceTypeface, trialSize)

        for (word in words) {
            val wordWidth = word.sumOf { measureChar(it, trialSize).toDouble() }.toFloat()

            if (wordWidth > boxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = mutableListOf()
                    currentWidth = 0f
                }
                var chunk = mutableListOf<CharSpec>()
                var chunkWidth = 0f
                for (spec in word) {
                    val cw = measureChar(spec, trialSize)
                    if (chunkWidth + cw > boxWidth && chunk.isNotEmpty()) {
                        lines.add(chunk)
                        chunk = mutableListOf()
                        chunkWidth = 0f
                    }
                    chunk.add(spec)
                    chunkWidth += cw
                }
                if (chunk.isNotEmpty()) {
                    currentLine = chunk
                    currentWidth = chunkWidth
                }
                continue
            }

            val needed = wordWidth + (if (currentLine.isNotEmpty()) spaceWidth else 0f)
            if (currentWidth + needed > boxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = mutableListOf()
                currentWidth = 0f
            }

            if (currentLine.isNotEmpty()) {
                currentLine.add(CharSpec(' ', referenceTypeface, 0, 1f, 0f, 0f, Color.TRANSPARENT))
                currentWidth += spaceWidth
            }
            currentLine.addAll(word)
            currentWidth += wordWidth
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        return lines
    }

    private fun lineWidth(line: List<CharSpec>, trialSize: Float): Float {
        return line.sumOf { measureChar(it, trialSize).toDouble() }.toFloat()
    }

    private fun fitTextToBox(
        words: List<List<CharSpec>>,
        boxWidth: Float,
        boxHeight: Float,
        referenceTypeface: Typeface
    ): Pair<List<List<CharSpec>>, Float> {
        var lo = 8f
        var hi = 400f
        var bestSize = lo
        var bestLines = wrapAtSize(words, lo, boxWidth, referenceTypeface)

        while (lo <= hi) {
            val mid = (lo + hi) / 2f
            val lines = wrapAtSize(words, mid, boxWidth, referenceTypeface)
            val maxWidth = lines.maxOfOrNull { lineWidth(it, mid) } ?: 0f

            val metricsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            metricsPaint.typeface = referenceTypeface
            metricsPaint.textSize = mid
            val fm = metricsPaint.fontMetrics
            val lineHeight = (fm.descent - fm.ascent) * 1.05f
            val totalHeight = lineHeight * lines.size

            if (maxWidth <= boxWidth && totalHeight <= boxHeight) {
                bestSize = mid
                bestLines = lines
                lo = mid + 1f
            } else {
                hi = mid - 1f
            }
        }

        return Pair(bestLines, bestSize)
    }

    private fun drawWrappedText(
        canvas: Canvas,
        lines: List<List<CharSpec>>,
        textSize: Float,
        boxLeft: Float,
        boxWidth: Float,
        startY: Float,
        lineHeight: Float,
        random: Random
    ) {
        var y = startY

        for (line in lines) {
            val totalWidth = lineWidth(line, textSize)
            var cursorX = boxLeft + (boxWidth - totalWidth) / 2f

            val driftAmplitude = textSize * (0.03f + random.nextFloat() * 0.05f)
            val driftPhase = random.nextFloat() * (2 * Math.PI).toFloat()
            val driftFrequency = 1.5f + random.nextFloat() * 1.5f

            for (spec in line) {
                val charSize = textSize * spec.sizeFactor
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.typeface = spec.typeface
                p.textSize = charSize
                p.textSkewX = spec.skew
                p.style = Paint.Style.FILL
                p.color = spec.colorOverride

                val charWidth = p.measureText(spec.ch.toString())

                if (spec.ch != ' ') {
                    val progress = ((cursorX - boxLeft) / boxWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val lineDrift = driftAmplitude * sin(driftPhase + progress * driftFrequency * 2 * Math.PI.toFloat())
                    val baselineJitter = (spec.baselineJitter * textSize * 0.06f) - (textSize * 0.03f)

                    canvas.save()
                    canvas.translate(cursorX, y + baselineJitter + lineDrift)

                    canvas.drawText(spec.ch.toString(), 0f, 0f, p)

                    val thickness = thicknessFor(spec.typefaceIndex)
                    if (thickness > 0f) {
                        val strokePaint = Paint(p)
                        strokePaint.style = Paint.Style.STROKE
                        strokePaint.strokeWidth = charSize * thickness
                        canvas.drawText(spec.ch.toString(), 0f, 0f, strokePaint)
                    }

                    canvas.restore()
                }

                cursorX += charWidth
            }

            y += lineHeight
        }
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
