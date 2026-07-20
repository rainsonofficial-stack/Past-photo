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

    private data class CharSpec(
        val ch: Char,
        val typeface: Typeface,
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

        // Pre-generate ALL per-character rendering parameters ONCE (font,
        // size jitter, skew, color jitter). These exact same values are used
        // both to MEASURE text during wrapping/sizing AND to actually draw
        // it — guaranteeing what gets measured is exactly what gets rendered,
        // eliminating the width mismatch that caused overflow before.
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

    // Builds one CharSpec per letter (font assignment cycles per letter
    // identity, same rule as before — 2nd "a" anywhere uses font 2, etc.),
    // plus one CharSpec per space between words. Punctuation/digits use the
    // first typeface with no skew.
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
                val skew: Float
                if (ch.isLetter()) {
                    val key = ch.lowercaseChar()
                    val idx = perLetterCycleIndex.getOrDefault(key, 0)
                    typeface = typefaces[idx % typefaces.size]
                    perLetterCycleIndex[key] = idx + 1
                    skew = (random.nextFloat() * 0.2f) - 0.1f
                } else {
                    typeface = typefaces.first()
                    skew = 0f
                }

                val sizeFactor = 0.96f + random.nextFloat() * 0.06f // tight, width-safe
                val baselineJitter = random.nextFloat() // 0..1, scaled at draw time
                val shade = 0.85f + random.nextFloat() * 0.3f
                val jitteredColor = Color.rgb(
                    (Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255)
                )
                val colorWithAlpha = Color.argb(alpha, Color.red(jitteredColor), Color.green(jitteredColor), Color.blue(jitteredColor))

                CharSpec(ch, typeface, sizeFactor, skew, baselineJitter, colorWithAlpha)
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

    // Wraps words into lines at a given trial size. Falls back to breaking
    // mid-word only if a single word is wider than the box even at this
    // size — never silently drops/cuts text.
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
                // Single word too long even alone — break it mid-word as a
                // last resort so nothing gets silently cut off.
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
                currentLine.add(CharSpec(' ', referenceTypeface, 1f, 0f, 0f, Color.TRANSPARENT))
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

    // Binary-searches the largest text size where the wrapped result fits
    // both the box width AND height. If text is too long to fit even at the
    // smallest allowed size, uses the smallest size anyway (last resort —
    // still wraps rather than cutting off, may extend slightly past bottom
    // margin only in extreme cases).
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
            // Center each line horizontally within the box.
            var cursorX = boxLeft + (boxWidth - totalWidth) / 2f

            // Whole-line wobble, restored — safe now since it only affects
            // vertical position, never width/wrapping.
            val driftAmplitude = textSize * (0.03f + random.nextFloat() * 0.05f)
            val driftPhase = random.nextFloat() * (2 * Math.PI).toFloat()
            val driftFrequency = 1.5f + random.nextFloat() * 1.5f

            for (spec in line) {
                val charSize = textSize * spec.sizeFactor
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.typeface = spec.typeface
                p.textSize = charSize
                p.textSkewX = spec.skew
                p.color = spec.colorOverride

                val charWidth = p.measureText(spec.ch.toString())

                if (spec.ch != ' ') {
                    val progress = ((cursorX - boxLeft) / boxWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val lineDrift = driftAmplitude * sin(driftPhase + progress * driftFrequency * 2 * Math.PI.toFloat())
                    val baselineJitter = (spec.baselineJitter * textSize * 0.06f) - (textSize * 0.03f)

                    canvas.save()
                    canvas.translate(cursorX, y + baselineJitter + lineDrift)
                    canvas.drawText(spec.ch.toString(), 0f, 0f, p)
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
