package com.worm.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import com.worm.app.MainActivity
import com.worm.app.R
import com.worm.app.convert.SilkSpinner
import com.worm.app.download.Downloads

/**
 * Worm's notifications. On Android 16+ progress uses the new progress style: a little worm crawls
 * along a segmented bar (one segment per file), with a status-bar chip showing the percentage.
 * Older Android gets a standard progress bar. Every notification has something useful to tap.
 */
object WormNotify {
    const val CH_DOWNLOADS = "downloads"
    const val CH_CONVERT = "converters"
    const val CH_WORMHOLE = "wormhole"
    private const val PINK = 0xFFD552A3.toInt()
    private val SEGMENT_COLORS = intArrayOf(0xFFD552A3.toInt(), 0xFF8E1EA2.toInt(), 0xFF98E8DE.toInt(), 0xFFE46FB4.toInt())

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        listOf(
            CH_DOWNLOADS to "Downloads",
            CH_CONVERT to "Converting books",
            CH_WORMHOLE to "Wormhole transfers",
        ).forEach { (id, name) ->
            if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** Opens Worm (optionally straight to a book or a screen). */
    fun open(c: Context, requestCode: Int, bookPath: String? = null, screen: String? = null): PendingIntent =
        PendingIntent.getActivity(
            c, requestCode,
            Intent(c, MainActivity::class.java).apply {
                action = if (bookPath != null) MainActivity.ACTION_OPEN_BOOK else MainActivity.ACTION_OPEN_SCREEN
                bookPath?.let { putExtra(MainActivity.EXTRA_BOOK_PATH, it) }
                screen?.let { putExtra(MainActivity.EXTRA_SCREEN, it) }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A button that runs one of [NotifyActions]' actions. */
    fun action(c: Context, requestCode: Int, label: String, action: String, extra: String? = null): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, NotifyActions::class.java).setAction(action).apply { extra?.let { putExtra(NotifyActions.EXTRA_ID, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_worm), label, pi).build()
    }

    fun cover(path: String?): Bitmap? = path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }

    /**
     * An ongoing progress notification.
     * [fraction] < 0 means "working on it" (indeterminate). [segments] are relative sizes (e.g. track
     * lengths) drawn as alternating Worm colours. [chip] is the short status-bar text.
     */
    fun progress(
        c: Context,
        channel: String,
        title: String,
        text: String,
        fraction: Float,
        segments: List<Long> = emptyList(),
        largeIcon: Bitmap? = null,
        actions: List<Notification.Action> = emptyList(),
        contentIntent: PendingIntent? = null,
        chip: String? = null,
    ): Notification {
        if (Build.VERSION.SDK_INT >= 36) {
            val max = 1000
            val style = Notification.ProgressStyle()
                .setProgressTrackerIcon(Icon.createWithResource(c, R.drawable.ic_progress_worm))
                .setProgressIndeterminate(fraction < 0)
                .setStyledByProgress(true)
            val total = segments.sum()
            if (segments.size > 1 && total > 0) {
                // One segment per file; lengths scaled so they add up to exactly [max].
                var used = 0
                val segs = segments.mapIndexed { i, len ->
                    val l = if (i == segments.lastIndex) max - used else ((len.toDouble() / total) * max).toInt().coerceAtLeast(1)
                    used += l
                    Notification.ProgressStyle.Segment(l).setColor(SEGMENT_COLORS[i % SEGMENT_COLORS.size])
                }
                style.setProgressSegments(segs)
            } else style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(max).setColor(PINK)))
            if (fraction >= 0) style.setProgress((fraction.coerceIn(0f, 1f) * max).toInt())
            return Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_worm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setRequestPromotedOngoing(true)
                .apply {
                    (chip ?: if (fraction >= 0) "${(fraction * 100).toInt()}%" else null)?.let { setShortCriticalText(it) }
                    largeIcon?.let { setLargeIcon(it) }
                    contentIntent?.let { setContentIntent(it) }
                    actions.forEach { addAction(it) }
                }
                .build()
        }
        return NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_worm)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(1000, (fraction.coerceIn(0f, 1f) * 1000).toInt(), fraction < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(PINK)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentIntent)
            .apply {
                actions.forEach { a -> addAction(NotificationCompat.Action(R.drawable.ic_stat_worm, a.title, a.actionIntent)) }
            }
            .build()
    }

    /** A "finished" notification with buttons (e.g. Read now). */
    fun done(
        c: Context,
        channel: String,
        title: String,
        text: String,
        largeIcon: Bitmap? = null,
        contentIntent: PendingIntent? = null,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
    ): Notification = NotificationCompat.Builder(c, channel)
        .setSmallIcon(R.drawable.ic_stat_worm)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setLargeIcon(largeIcon)
        .setColor(PINK)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .apply { actions.forEach { (label, pi) -> addAction(NotificationCompat.Action(R.drawable.ic_stat_worm, label, pi)) } }
        .build()
}

/** Handles notification buttons that don't need to open the app. */
class NotifyActions : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)
        when (intent.action) {
            PAUSE_DOWNLOAD -> id?.let { Downloads.load(c); Downloads.pause(c, it) }
            CANCEL_DOWNLOAD -> id?.let { Downloads.load(c); Downloads.remove(c, it) }
            STOP_SPINNING -> SilkSpinner.cancel(c)
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val PAUSE_DOWNLOAD = "com.worm.app.PAUSE_DOWNLOAD"
        const val CANCEL_DOWNLOAD = "com.worm.app.CANCEL_DOWNLOAD"
        const val STOP_SPINNING = "com.worm.app.STOP_SPINNING"
    }
}
