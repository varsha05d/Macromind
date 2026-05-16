package com.macromind.foodscanner

import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * DebugHelper.kt
 *
 * Shows a scrollable dialog with the raw OCR text so you can see
 * exactly what ML Kit is reading from the image.
 *
 * USAGE — add this call inside CropActivity.kt, in the runOCR()
 * addOnSuccessListener block, RIGHT AFTER you have the rawText:
 *
 *     DebugHelper.showOcrResult(this, rawText, ScanSession.currentMode.name)
 *
 * REMOVE this once parsing is working correctly.
 */
object DebugHelper {

    fun showOcrResult(context: Context, rawText: String, mode: String) {
        val scrollView = ScrollView(context)
        val textView   = TextView(context).apply {
            text       = rawText.ifBlank { "(empty — nothing was read)" }
            textSize   = 12f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)   // lets you copy the text
        }
        scrollView.addView(textView)

        AlertDialog.Builder(context)
            .setTitle("🔍 Raw OCR Output [$mode]")
            .setView(scrollView)
            .setPositiveButton("OK") { _, _ -> }
            .setNegativeButton("This looks wrong") { _, _ ->
                // Tapping this tells you the OCR itself is the problem
                // (lighting, rotation, crop area)
                android.widget.Toast.makeText(
                    context,
                    "Try: better lighting, hold steady, crop tighter",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }
}
