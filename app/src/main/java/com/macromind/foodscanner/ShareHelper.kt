package com.macromind.foodscanner

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * ShareHelper — Renders an AnalysisResult into a branded PNG and shares it.
 *
 * Uses Canvas drawing (no external libraries).
 * Produces a ~1080×1920-ish image that looks good on social media.
 */
object ShareHelper {

    private const val TAG = "MacroMind_Share"
    private const val WIDTH  = 1080
    private const val AUTHORITY = "com.macromind.foodscanner.fileprovider"

    fun shareResult(context: Context, result: OfflineAnalyzer.AnalysisResult) {
        try {
            val bitmap = renderResultImage(context, result)
            val uri = saveBitmapToCache(context, bitmap)
            bitmap.recycle()

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, result.summary)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share MacroMind Result"))
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}", e)
            android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderResultImage(context: Context, result: OfflineAnalyzer.AnalysisResult): Bitmap {
        // Dynamic height based on content
        val additiveRows = result.detectedAdditives.take(6).size
        val height = 720 + (additiveRows * 60).coerceAtLeast(80)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // ── Background ──
        val bgPaint = Paint().apply { color = Color.parseColor("#F7F8FA") }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), bgPaint)

        // ── Reusable paints ──
        val darkBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1D26"); textSize = 48f; typeface = Typeface.DEFAULT_BOLD
        }
        val darkNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1D26"); textSize = 32f
        }
        val dimText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280"); textSize = 28f
        }
        val accentText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }

        var y = 60f

        // ── Header: "MacroMind" + "Offline" badge ──
        canvas.drawText("MacroMind", 48f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1D26"); textSize = 36f; typeface = Typeface.DEFAULT_BOLD
        })
        canvas.drawText("Offline", WIDTH - 200f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2563EB"); textSize = 26f
        })
        y += 20f

        // ── Divider ──
        canvas.drawRect(48f, y, WIDTH - 48f, y + 2f, Paint().apply {
            color = Color.parseColor("#E5E7EB")
        })
        y += 40f

        // ── Verdict hero ──
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 80f }
        canvas.drawText(result.verdictEmoji, WIDTH / 2f - 40f, y + 70f, emojiPaint)
        y += 100f

        val verdictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1D26"); textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(result.verdict.uppercase(), WIDTH / 2f, y, verdictPaint)
        y += 50f

        val confPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280"); textSize = 30f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${(result.confidence * 100).toInt()}% confident  ·  ${result.category}",
            WIDTH / 2f, y, confPaint)
        y += 60f

        // ── Confidence bar ──
        val barX = 120f; val barW = WIDTH - 240f; val barH = 12f
        val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E5E7EB") }
        canvas.drawRoundRect(barX, y, barX + barW, y + barH, 6f, 6f, barBgPaint)

        val fillW = barW * result.confidence
        val verdictBarColor = when (result.verdict.lowercase()) {
            "healthy" -> "#16A34A"; "moderate" -> "#D97706"; else -> "#DC2626"
        }
        val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(verdictBarColor)
        }
        canvas.drawRoundRect(barX, y, barX + fillW, y + barH, 6f, 6f, barFillPaint)
        y += 50f

        // ── Divider ──
        canvas.drawRect(48f, y, WIDTH - 48f, y + 1f, Paint().apply {
            color = Color.parseColor("#E5E7EB")
        })
        y += 35f

        // ── Additives summary ──
        val addIcon = if (result.harmfulAdditiveCount > 0) "⚠️" else "✅"
        val addLabel = when {
            result.additiveCount == 0 -> "$addIcon  No additives detected"
            result.harmfulAdditiveCount > 0 ->
                "$addIcon  ${result.additiveCount} additives (${result.harmfulAdditiveCount} concerning)"
            else -> "$addIcon  ${result.additiveCount} additives found — all safe"
        }
        canvas.drawText(addLabel, 48f, y, darkNormal)
        y += 50f

        // ── Additive list (top 6) ──
        result.detectedAdditives.take(6).forEach { additive ->
            val sevColor = when (additive.severity.lowercase()) {
                "high" -> "#DC2626"; "moderate" -> "#D97706"; "minor" -> "#CA8A04"; else -> "#16A34A"
            }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(sevColor) }
            canvas.drawCircle(72f, y - 8f, 8f, dot)

            val name = if (additive.code.isNotBlank()) "${additive.code} — ${additive.name}" else additive.name
            canvas.drawText(name, 100f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A1D26"); textSize = 26f
            })

            val sevLabel = when (additive.severity.lowercase()) {
                "high" -> "High"; "moderate" -> "Moderate"; "minor" -> "Low"; else -> "Safe"
            }
            val sevLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(sevColor); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(sevLabel, WIDTH - 200f, y, sevLabelPaint)
            y += 48f
        }

        y += 20f

        // ── Footer ──
        canvas.drawRect(48f, y, WIDTH - 48f, y + 1f, Paint().apply {
            color = Color.parseColor("#E5E7EB")
        })
        y += 35f
        canvas.drawText("Scanned with MacroMind · 100% Offline AI", 48f, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#9CA3AF"); textSize = 24f
            })

        return bitmap
    }

    private fun saveBitmapToCache(context: Context, bitmap: Bitmap): android.net.Uri {
        val dir = File(context.cacheDir, "shared_images")
        dir.mkdirs()
        val file = File(dir, "macromind_result_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }
}
