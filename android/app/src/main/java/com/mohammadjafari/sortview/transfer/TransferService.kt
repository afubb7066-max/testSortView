package com.mohammadjafari.sortview.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mohammadjafari.sortview.MainActivity
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.fs.TransferHub
import com.mohammadjafari.sortview.fs.TransferProgress
import com.mohammadjafari.sortview.ui.resolveStatus
import com.mohammadjafari.sortview.ui.statusOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the transfer process alive while the queue
 * is busy and shows a live progress notification with a Stop action.
 *
 * Without it, backgrounding the app mid-transfer let the system kill the
 * process — the source files always survived (they are only deleted after
 * the size verify) but the queue died in half. The transfer engine itself
 * lives in the application-scoped [TransferHub]; this service only watches
 * [TransferHub.progress], renders the notification and stops itself once
 * the queue has stayed idle for a short grace period.
 *
 * Manifest: foregroundServiceType="dataSync" + FOREGROUND_SERVICE /
 * FOREGROUND_SERVICE_DATA_SYNC permissions (required by API 34+; on
 * Android 15 a dataSync service is capped at 6 h per day — far above a
 * normal sorting round). The service is only started while the app is in
 * the foreground (right before the enqueue loop), which satisfies the
 * background-start restrictions. START_NOT_STICKY on purpose: if the
 * process is still killed, a restarted empty service would be misleading.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notif_channel_desc)
        mgr.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Counted drops are buffered in the hub; the next ViewModel
            // reconciles the undo bookkeeping with them (takeDropped).
            TransferHub.cancelFromService(applicationContext)
        }
        // Must land within ~5 s of startForegroundService — do it first.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(TransferHub.progress.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watchJob == null) {
            watchJob = scope.launch { watchLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchLoop() {
        var idleTicks = 0
        while (true) {
            val p = TransferHub.progress.value
            updateNotification(p)
            // engine state, not just the mirrored snapshot — authoritative.
            val idle = TransferHub.isIdle()
            idleTicks = if (idle) idleTicks + 1 else 0
            // Stop after ~1.5 s of continuous idle. The grace period rides
            // out the gap between startForegroundService and the first
            // enqueue landing in the engine (start is called just before
            // the enqueue loop), so a not-yet-busy queue is not mistaken
            // for a finished one.
            if (idle && idleTicks >= IDLE_TICKS_TO_STOP) {
                shutdown()
                return
            }
            delay(POLL_MS)
        }
    }

    private fun updateNotification(p: TransferProgress) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(p))
    }

    private fun buildNotification(p: TransferProgress): Notification {
        val text = when {
            p.idle -> resolveStatus(statusOf(R.string.notif_idle))
            p.active > 0 && p.queued > 0 -> resolveStatus(
                statusOf(R.string.notif_moving, "active" to p.active, "queued" to p.queued),
            )
            p.active > 0 -> resolveStatus(statusOf(R.string.notif_active_only, "active" to p.active))
            else -> resolveStatus(statusOf(R.string.notif_queued_only, "queued" to p.queued))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            // Phase 5: deterministic per-batch progress bar (file count).
            // A multi-gigabyte queue on a phone needs a visible sense of
            // motion; idle batches carry batchTotal == 0, which renders
            // no bar at all.
            .setProgress(p.batchTotal, p.batchDone, false)
            .addAction(0, resolveStatus(statusOf(R.string.notif_cancel)), cancelPendingIntent())
            .build()
    }

    /** Tapping the notification returns to the sorting screen. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, REQ_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, TransferService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, REQ_CANCEL, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "sortview_transfers"
        const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "com.mohammadjafari.sortview.transfer.CANCEL"
        private const val REQ_OPEN = 1
        private const val REQ_CANCEL = 2
        private const val POLL_MS = 500L
        private const val IDLE_TICKS_TO_STOP = 3

        /** Start (or re-attach) the foreground service for a new batch. */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
