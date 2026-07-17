package com.makia.jtchart.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.makia.jtchart.domain.market.Query
import com.makia.jtchart.domain.signal.ChartSignal
import java.util.Locale
import kotlin.math.absoluteValue

interface SignalNotifier {
    fun notify(query: Query, signal: ChartSignal)
}

class AndroidSignalNotifier(private val context: Context) : SignalNotifier {
    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sentStore = appContext.getSharedPreferences("jt-chart-signal-notifications", Context.MODE_PRIVATE)

    override fun notify(query: Query, signal: ChartSignal) {
        if (!canPostNotifications()) return
        createChannel()
        val key = "${query.canonicalKey}:${signal.id}"
        if (hasSent(key)) return

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                key.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val message = "${query.symbol} ${query.interval.wireName} · " +
            "收盘 ${format(signal.close)} · 指标 ${format(signal.score)}"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("${signal.text} ${query.symbol}")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        notificationManager.notify(key.hashCode().absoluteValue, notification)
        markSent(key)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(CHANNEL_ID, "JT Chart 信号", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "抄底、逃顶等交易信号提醒"
        notificationManager.createNotificationChannel(channel)
    }

    private fun hasSent(key: String): Boolean = key in sentKeys()

    private fun markSent(key: String) {
        val next = (sentKeys() + key).takeLast(MAX_SENT_KEYS).toSet()
        sentStore.edit().putStringSet(SENT_KEYS, next).apply()
    }

    private fun sentKeys(): List<String> =
        sentStore.getStringSet(SENT_KEYS, emptySet()).orEmpty().toList()

    private fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

    companion object {
        private const val CHANNEL_ID = "jt-chart-signals"
        private const val SENT_KEYS = "sent_keys"
        private const val MAX_SENT_KEYS = 1000
    }
}

object NoopSignalNotifier : SignalNotifier {
    override fun notify(query: Query, signal: ChartSignal) = Unit
}
