package com.nex.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.util.Log

class NexNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Filtrar apps comuns (WhatsApp, Telegram, SMS)
        if (packageName.contains("whatsapp") || packageName.contains("mms") || packageName.contains("telegram")) {
            Log.d("NEX_NOTIF", "Notificação de $title: $text")
            
            // Enviar para o NexService para ele decidir se fala ou não
            val intent = Intent("com.nex.assistant.NEW_NOTIFICATION")
            intent.putExtra("sender", title)
            intent.putExtra("message", text)
            intent.putExtra("package", packageName)
            sendBroadcast(intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notificação lida ou removida
    }
}
