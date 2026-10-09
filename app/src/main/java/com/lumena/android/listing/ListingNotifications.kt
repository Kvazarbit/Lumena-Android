package com.lumena.android.listing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lumena.android.settings.ListingAttentionStore
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.modules.ListingAttentionModule
import java.util.concurrent.Executors

/** One background thread for all listing work: notification callbacks must stay fast. */
internal object ListingWork {
    private val executor = Executors.newSingleThreadExecutor()

    fun execute(block: () -> Unit) {
        executor.execute { block() }
    }
}

/**
 * Receives what marketplace apps already push to this phone. Only watched
 * sources are read; everything else returns at the first line.
 */
class ListingNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Catch listings posted while the listener was not bound; duplicates are dropped.
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        active.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        // A disabled module reads and stores nothing.
        if (!ModuleRegistry.isEnabled(ListingAttentionModule.ID)) return
        if (!ListingNoticeExtractor.isWatchedSource(sbn.packageName)) return
        val raw = rawCapture(sbn)
        if (ListingNoticeExtractor.isPrivateMessage(raw)) return
        val original = sbn.notification.contentIntent
        val app = applicationContext
        ListingWork.execute {
            val records = runCatching { ListingAttentionStore.ingest(app, raw) }.getOrDefault(emptyList())
            records
                .filter { it.decision == ListingDecision.ALERT }
                .forEach { record -> runCatching { ListingAlerts.post(app, record, original) } }
        }
    }

    private fun rawCapture(sbn: StatusBarNotification): ListingRawCapture {
        val notification = sbn.notification
        val extras = notification.extras
        fun text(key: String): String = extras?.getCharSequence(key)?.toString().orEmpty()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        return ListingRawCapture(
            source = sbn.packageName,
            category = notification.category,
            groupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            messaging = extras?.containsKey(Notification.EXTRA_MESSAGES) == true,
            title = text(Notification.EXTRA_TITLE),
            text = text(Notification.EXTRA_BIG_TEXT).ifBlank { text(Notification.EXTRA_TEXT) },
            lines = lines,
            at = sbn.postTime
        )
    }
}

object ListingAlerts {
    const val CHANNEL_ID = "lumena_listing_alerts"
    const val ACTION_FEEDBACK = "com.lumena.android.listing.FEEDBACK"
    const val EXTRA_RECORD_ID = "com.lumena.android.listing.RECORD_ID"
    const val EXTRA_USEFUL = "com.lumena.android.listing.USEFUL"
    const val EXTRA_NOTIFICATION_ID = "com.lumena.android.listing.NOTIFICATION_ID"

    fun canAlert(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Loud alert for a rare listing. Tapping it opens the marketplace app's
     * own notification target; the two actions are the owner's 👍/👎.
     */
    fun post(context: Context, record: ListingRecord, original: PendingIntent?) {
        if (!canAlert(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Рідкісні оголошення", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Lumena: оголошення, варті уваги зараз"
                }
            )
        }
        val notificationId = record.id.hashCode()
        val open = original ?: context.packageManager.getLaunchIntentForPackage(record.source)?.let { launch ->
            PendingIntent.getActivity(
                context,
                notificationId,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val body = buildString {
            if (record.text.isNotBlank()) appendLine(record.text)
            append("Чому: ")
            append(record.reasons.joinToString("; "))
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(record.title.ifBlank { "Рідкісне оголошення" }.take(120))
            .setContentText(record.reasons.joinToString("; ").take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1000)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .addAction(0, "👍 Варте уваги", feedbackIntent(context, record.id, true, notificationId))
            .addAction(0, "👎 Не те", feedbackIntent(context, record.id, false, notificationId))
        if (open != null) builder.setContentIntent(open)
        manager.notify(notificationId, builder.build())
    }

    private fun feedbackIntent(
        context: Context,
        recordId: String,
        useful: Boolean,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, ListingFeedbackReceiver::class.java)
            .setAction(ACTION_FEEDBACK)
            .putExtra(EXTRA_RECORD_ID, recordId)
            .putExtra(EXTRA_USEFUL, useful)
            .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        return PendingIntent.getBroadcast(
            context,
            notificationId * 2 + if (useful) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** 👍/👎 from an alert: records the label, then removes the alert. */
class ListingFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ListingAlerts.ACTION_FEEDBACK) return
        val recordId = intent.getStringExtra(ListingAlerts.EXTRA_RECORD_ID) ?: return
        val useful = intent.getBooleanExtra(ListingAlerts.EXTRA_USEFUL, false)
        val notificationId = intent.getIntExtra(ListingAlerts.EXTRA_NOTIFICATION_ID, 0)
        val app = context.applicationContext
        val pending = goAsync()
        ListingWork.execute {
            try {
                runCatching { ListingAttentionStore.feedback(app, recordId, useful) }
                app.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }
}
