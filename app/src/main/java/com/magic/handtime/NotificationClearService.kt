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
            cancelAllNotifications()
        } catch (e: Exception) { /* listener not bound, ignore */ }
    }
}
