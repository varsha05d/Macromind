package com.macromind.foodscanner

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.macromind.foodscanner.data.AppDatabase
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity — Premium launch screen with scan history access.
 * Staggered fade-in animation on all elements.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge immersive layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val heroEmoji     = findViewById<TextView>(R.id.heroEmoji)
        val heroTitle     = findViewById<TextView>(R.id.heroTitle)
        val heroSubtitle  = findViewById<TextView>(R.id.heroSubtitle)
        val heroDesc      = findViewById<TextView>(R.id.heroDesc)
        val featureRow    = findViewById<View>(R.id.featureRow)
        val startButton   = findViewById<Button>(R.id.startScanButton)
        val historyButton = findViewById<Button>(R.id.historyButton)
        val scanCount     = findViewById<TextView>(R.id.scanCountText)

        // ── Observe scan count ───────────────────────────────────────────
        lifecycleScope.launch {
            AppDatabase.get(this@MainActivity).scanHistoryDao().getCount()
                .collectLatest { count ->
                    scanCount.text = when {
                        count == 0 -> ""
                        count == 1 -> "1 scan saved"
                        else -> "$count scans saved"
                    }
                    scanCount.visibility = if (count > 0) View.VISIBLE else View.GONE
                }
        }

        // ── Staggered entrance animation ──────────────────────────────────
        val views = listOf(heroEmoji, heroTitle, heroSubtitle, heroDesc, featureRow, startButton, historyButton)
        views.forEach { v ->
            v.alpha = 0f
            v.translationY = 30f
        }

        views.forEachIndexed { index, v ->
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L + index * 60L)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // ── Start scan ────────────────────────────────────────────────────
        startButton.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // ── History ───────────────────────────────────────────────────────
        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }
}
