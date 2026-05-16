package com.macromind.foodscanner

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * SplashActivity — Animated brand splash screen.
 *
 * Shows for 1.2 seconds with snappy entrance animations,
 * then navigates to MainActivity.
 *
 * Works on all API levels (API 24+). Does NOT use the Android 12+
 * SplashScreen API — that can be added later as an enhancement.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val emoji    = findViewById<TextView>(R.id.splashEmoji)
        val title    = findViewById<TextView>(R.id.splashTitle)
        val subtitle = findViewById<TextView>(R.id.splashSubtitle)
        val tagline  = findViewById<TextView>(R.id.splashTagline)
        val version  = findViewById<TextView>(R.id.splashVersion)

        // Start invisible
        listOf(emoji, title, subtitle, tagline, version).forEach {
            it.alpha = 0f
            it.translationY = 16f
        }
        emoji.scaleX = 0f; emoji.scaleY = 0f

        // ── Snappy staggered entrance ──
        emoji.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(350).setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        title.animate()
            .alpha(1f).translationY(0f)
            .setDuration(250).setStartDelay(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        subtitle.animate()
            .alpha(1f).translationY(0f)
            .setDuration(250).setStartDelay(300)
            .setInterpolator(DecelerateInterpolator())
            .start()

        tagline.animate()
            .alpha(1f).translationY(0f)
            .setDuration(200).setStartDelay(400)
            .setInterpolator(DecelerateInterpolator())
            .start()

        version.animate()
            .alpha(0.5f).translationY(0f)
            .setDuration(200).setStartDelay(450)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // ── Navigate to MainActivity after 1.2s ──
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1200)
    }
}
