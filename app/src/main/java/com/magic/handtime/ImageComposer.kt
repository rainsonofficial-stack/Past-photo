package com.magic.handtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.random.Random

object ImageComposer {

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
        val original = BitmapFactory.decodeFile(baseImagePath)
            ?: throw IllegalStateException("Base image not found")
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
        val alpha = ((opacityPct.coerceIn(0, 100)) / 100f * 255).toInt()
        val colorWithAlpha = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        paint.typeface = typeface
        paint.color = colorWithAlpha

        // Force multi-line for anything longer than 2 words, so the app doesn't
        // squeeze a long phrase onto a single huge-font line. Groups words two
        // at a time; StaticLayout will further wrap a pair if it's still too wide.
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

        canvas.save()
        canvas.rotate(rotationDeg, centerX, centerY)
        canvas.translate(textX, textY)
        drawHandwrittenText(canvas, finalLayout, paint, displayText, colorWithAlpha)
        canvas.restore()

        return bitmap
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
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
    }

    private fun drawHandwrittenText(canvas: Canvas, layout: StaticLayout, basePaint: TextPaint, fullText: String, colorWithAlpha: Int) {
        val random = Random(System.nanoTime())
        for (lineIndex in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineBaseline = layout.getLineBaseline(lineIndex).toFloat()
            var cursorX = layout.getLineLeft(lineIndex)

            for (i in lineStart until lineEnd) {
                val ch = fullText[i]
                if (ch == '\n') continue

                val charPaint = TextPaint(basePaint)
                charPaint.color = colorWithAlpha

                // Variable 1: size jitter (~ ±6%)
                val sizeJitter = basePaint.textSize * (0.94f + random.nextFloat() * 0.12f)
                charPaint.textSize = sizeJitter

                // Variable 2: baseline (vertical) jitter
                val baselineJitter = (random.nextFloat() * basePaint.textSize * 0.06f) - (basePaint.textSize * 0.03f)

                // Variable 3: variable-font weight axis jitter (per letter stroke thickness)
                val weight = 350 + random.nextInt(300) // 350–650
                try {
                    charPaint.fontVariationSettings = "'wght' $weight"
                } catch (e: Exception) { /* font may not support the axis; ignore */ }

                val rotJitter = (random.nextFloat() * 6f) - 3f
                val charWidth = charPaint.measureText(ch.toString())

                canvas.save()
                canvas.translate(cursorX, lineBaseline + baselineJitter)
                canvas.rotate(rotJitter, charWidth / 2f, -basePaint.textSize / 3f)
                canvas.drawText(ch.toString(), 0f, 0f, charPaint)
                canvas.restore()

                cursorX += charWidth
            }
        }
    }

    private fun loadHandwritingTypeface(context: Context): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/handwriting.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
    }
}
