package com.magic.handtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class PollingService : Service() {

    private val client = OkHttpClient()
    private var job: Job? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("handtime_prefs", MODE_PRIVATE)
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "handtime_polling"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelId, "Background Activity", NotificationManager.IMPORTANCE_MIN)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("HandTime")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun startPolling() {
        if (job?.isActive == true) return

        val apiLink = prefs.getString("api_link", "") ?: ""
        val apiKey = prefs.getString("api_key", "value") ?: "value"

        job = CoroutineScope(Dispatchers.IO).launch {
            var lastValue = fetchCurrentValue(apiLink, apiKey)
            var changeCount = 0

            while (isActive) {
                delay(2000)
                val value = fetchCurrentValue(apiLink, apiKey)

                if (value.isNotBlank() && value != lastValue) {
                    changeCount++
                    lastValue = value

                    if (changeCount == 1) {
                        // Pairing word — absorbed silently, no trigger.
                        continue
                    } else {
                        // Second genuine change — the real spectator word.
                        launchTrigger(value)
                        stopSelf()
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

    private fun launchTrigger(term: String) {
        val intent = Intent(this, BlackScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("trigger_term", term)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
