package com.magic.handtime

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File

class BlackScreenActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("handtime_prefs", MODE_PRIVATE)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val term = intent.getStringExtra("trigger_term")
        if (term != null) {
            waitThenCompose(term)
        }
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun waitThenCompose(term: String) {
        Handler(Looper.getMainLooper()).postDelayed({ composeAndSave(term) }, 3000)
    }

    private fun composeAndSave(term: String) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val marginLeft = prefs.getInt("margin_left", 33)
                val marginRight = prefs.getInt("margin_right", 22)
                val marginTop = prefs.getInt("margin_top", 33)
                val marginBottom = prefs.getInt("margin_bottom", 33)
                val textColor = prefs.getString("text_color", "#000000") ?: "#000000"
                val textOpacity = prefs.getInt("text_opacity", 100)
                val textRotation = prefs.getInt("text_rotation", 0)

                val baseImagePath = File(filesDir, "base_image.jpg").absolutePath
                val finalBitmap = ImageComposer.composeImage(
                    context = applicationContext,
                    baseImagePath = baseImagePath,
                    text = term,
                    marginLeftPct = marginLeft,
                    marginRightPct = marginRight,
                    marginTopPct = marginTop,
                    marginBottomPct = marginBottom,
                    textColorHex = textColor,
                    opacityPct = textOpacity,
                    rotationDeg = textRotation.toFloat()
                )

                val timeMillis = SaveHelper.resolveTargetTime(applicationContext)
                SaveHelper.saveComposedImage(applicationContext, finalBitmap, timeMillis)

                withContext(Dispatchers.Main) {
                    vibrateDone()
                    finishAndReturnHome()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BlackScreenActivity, "Compose failed: ${e.message}", Toast.LENGTH_LONG).show()
                    finishAndReturnHome()
                }
            }
        }
    }

    private fun vibrateDone() {
        if (!prefs.getBoolean("vibrate_on_complete", true)) return
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 100, 120, 100, 120, 100)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun finishAndReturnHome() {
        moveTaskToBack(true)

        val homeIntent = Intent(Intent.ACTION_MAIN)
        homeIntent.addCategory(Intent.CATEGORY_HOME)
        homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(homeIntent)

        Handler(Looper.getMainLooper()).postDelayed({
            lockPhone()
            finish()
        }, 700)
    }

    private fun lockPhone() {
        if (!prefs.getBoolean("auto_lock", true)) return
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            } else {
                Toast.makeText(this, "Device Admin not active — phone won't auto-lock", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Lock failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
