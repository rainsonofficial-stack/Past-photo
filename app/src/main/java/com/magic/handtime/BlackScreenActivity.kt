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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

class BlackScreenActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient()
    private var polling: Job? = null

    private val ignoredValues = setOf("Paired", "Connected", "Confirmed")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("handtime_prefs", MODE_PRIVATE)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        startPolling()
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

    private fun startPolling() {
        val apiLink = prefs.getString("api_link", "") ?: ""
        val apiKey = prefs.getString("api_key", "value") ?: "value"

        polling = CoroutineScope(Dispatchers.IO).launch {
            var baseline = fetchCurrentValue(apiLink, apiKey)
            var pairingWordAbsorbed = false

            while (isActive) {
                delay(2000)
                val value = fetchCurrentValue(apiLink, apiKey)
                val isIgnored = ignoredValues.any { it.equals(value, ignoreCase = true) }

                if (value.isNotBlank() && value != baseline && !isIgnored) {
                    if (!pairingWordAbsorbed) {
                        baseline = value
                        pairingWordAbsorbed = true
                    } else {
                        withContext(Dispatchers.Main) { waitThenCompose(value) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun fetchCurrentValue(apiLink: String, apiKey: String): String {
        return try {
            val request = Request.Builder().url(apiLink).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return ""
                val json = JSONObject(body)
                if (json.has(apiKey)) json.getString(apiKey) else ""
            }
        } catch (e: Exception) { "" }
    }

    private fun waitThenCompose(term: String) {
        Handler(Looper.getMainLooper()).postDelayed({ composeAndSave(term) }, 5000)
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
        polling?.cancel()
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

    override fun onDestroy() {
        super.onDestroy()
        polling?.cancel()
    }
}
