package com.email.app.mail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.email.api.Action
import com.email.api.MessageSummary
import com.email.app.MainActivity
import com.email.app.R
import com.email.app.container
import com.email.app.data.Prefs
import com.email.app.net.ServerApi
import com.email.app.net.ServerException
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * New mail, while the app is closed: the server is asked what's arrived since the last look
 * (its /api/mail/new cursor), and each new message gets a notification, with Archive and Mark
 * read. The first look only notes where things are, so old mail isn't announced.
 */
class NewMailNotifier(private val context: Context, private val api: ServerApi, private val prefs: Prefs) {
    /** Asks what's new; [notify] says whether to tell (not while the app is open and showing it). */
    suspend fun check(notify: Boolean): List<MessageSummary> {
        val cursor = prefs.newMailCursor()
        val news = api.newMail(cursor)
        prefs.setNewMailCursor(news.cursor)
        if (cursor == null) return emptyList()
        if (notify) show(news.messages)
        return news.messages
    }

    fun show(messages: List<MessageSummary>) {
        if (messages.isEmpty() || !allowed()) return
        createChannel(context)
        val manager = NotificationManagerCompat.from(context)
        messages.take(MAX_SHOWN).forEach { m ->
            val from = m.from?.shown ?: "New mail"
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.brand))
                .setContentTitle(from)
                .setContentText(m.subject.ifBlank { m.preview })
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(m.subject, m.preview).filter { it.isNotBlank() }.joinToString("\n")))
                .setWhen(m.date)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setGroup(GROUP)
                .setContentIntent(openIntent(context, m.id))
                .addAction(0, "Archive", actionIntent(context, m.id, Action.ARCHIVE))
                .addAction(0, "Mark read", actionIntent(context, m.id, Action.READ))
                .build()
            @Suppress("MissingPermission")
            manager.notify(m.id, NOTIFICATION, notification)
        }
        if (messages.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.brand))
                .setContentTitle("${messages.size} new messages")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, null))
                .build()
            @Suppress("MissingPermission")
            manager.notify(SUMMARY_TAG, NOTIFICATION, summary)
        }
    }

    private fun allowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    companion object {
        const val CHANNEL = "new_mail"
        private const val GROUP = "new_mail"
        private const val NOTIFICATION = 1
        private const val SUMMARY_TAG = "summary"
        private const val MAX_SHOWN = 6

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_new_mail), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_new_mail_description)
                },
            )
        }

        fun cancel(context: Context, messageId: String) = NotificationManagerCompat.from(context).cancel(messageId, NOTIFICATION)

        private fun openIntent(context: Context, messageId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                messageId?.let { putExtra(MainActivity.EXTRA_MESSAGE, it) }
            }
            return PendingIntent.getActivity(context, messageId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun actionIntent(context: Context, messageId: String, action: Action): PendingIntent {
            val intent = Intent(context, NotificationActions::class.java).apply {
                putExtra(NotificationActions.EXTRA_MESSAGE, messageId)
                putExtra(NotificationActions.EXTRA_ACTION, action.name)
            }
            return PendingIntent.getBroadcast(context, (messageId + action.name).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** Archive and Mark read, done from the notification without opening the app. */
class NotificationActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MESSAGE) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { runCatching { Action.valueOf(it) }.getOrNull() } ?: return
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                c.api.act(listOf(id), action)
                c.mail.update(id) { it.copy(unread = false) }
            } catch (_: Exception) {
                // The message is still there, as it was; nothing else to do from a notification.
            } finally {
                NewMailNotifier.cancel(context, id)
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTION = "action"
    }
}

/** Looks for new mail every 15 minutes (as often as Android allows) while the app is closed. */
class NewMailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val settings = c.prefs.current()
        if (!settings.signedIn || !settings.notifications) return Result.success()
        return try {
            c.notifier.check(notify = !c.inForeground)
            Result.success()
        } catch (e: ServerException) {
            // Signed out on the server, or a refusal that won't change by trying again soon.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "new-mail"

        fun schedule(context: Context, on: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!on) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewMailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
