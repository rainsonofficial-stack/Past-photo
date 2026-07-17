package com.magic.handtime

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationClearService : NotificationListenerService() {

    companion object {
        var instance: NotificationClearService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    fun clearAllNotifications() {
        try {
            // Define the package names of the target apps
            val targetPackages = listOf(
                "com.android.chrome",
                "com.magic.performer", // Replace with the actual package name for Performer
                "com.magic.hacked"     // Replace with the actual package name for Hacked
            )

            // Loop through active notifications and cancel only the matches
            activeNotifications?.forEach { sbn ->
                if (targetPackages.contains(sbn.packageName)) {
                    cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) { /* listener not bound, ignore */ }
    }
}
