package com.android.services

import android.animation.ObjectAnimator
import android.animation.LinearInterpolator
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MatrixSuccessActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val totalDuration = 5500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_matrix_success)

        getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply()

        val card        = findViewById<View>(R.id.cardContainer)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvCheck     = findViewById<TextView>(R.id.tvCheck)
        val tvCountdown = findViewById<TextView>(R.id.tvCountdown)

        card.alpha  = 0f
        card.scaleX = 0.6f
        card.scaleY = 0.6f

        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(700)
            .setStartDelay(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        tvCheck.alpha = 0f
        tvCheck.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(800)
            .setInterpolator(DecelerateInterpolator())
            .start()

        val progressAnim = ObjectAnimator.ofInt(progressBar, "progress", 0, 100)
        progressAnim.duration = totalDuration
        progressAnim.interpolator = LinearInterpolator()
        progressAnim.start()

        val countdownSeconds = (totalDuration / 1000).toInt()
        for (i in countdownSeconds downTo 1) {
            handler.postDelayed({
                if (!isFinishing) tvCountdown.text = "Menutup dalam ${i}s..."
            }, (totalDuration - i * 1000L))
        }

        handler.postDelayed({
            if (!isFinishing) {
                card.animate()
                    .alpha(0f)
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(400)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { finish() }
                    .start()
            }
        }, totalDuration)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Blokir back selama animasi
    }
}
