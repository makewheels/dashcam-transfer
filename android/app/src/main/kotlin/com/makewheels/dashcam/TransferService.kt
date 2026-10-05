package com.makewheels.dashcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class TransferService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var thread: Thread? = null

  override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
    if (i == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    if ("pause" == i.action) {
      TransferEngine.stop(this)
      if (thread == null) stopSelf()
      return START_NOT_STICKY
    }
    startForeground(NOTICE, notification(this))
    if (!TransferEngine.BUSY.compareAndSet(false, true)) {
      if (thread == null) stopSelf()
      return START_NOT_STICKY
    }
    TransferEngine.stopped = false
    TransferEngine.pauseReason = "已暂停，可继续"
    getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", false).apply()
    val batch = i.getStringExtra("batch")
    val action = i.action
    val sourceTree = i.getStringExtra("tree")
    val cellular = i.getBooleanExtra("cellular", false)
    thread = Thread({
      val wake = (getSystemService(POWER_SERVICE) as PowerManager)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transfer:active")
      wake.acquire(6 * 60 * 60 * 1000L)
      try {
        val engine = TransferEngine(this)
        if ("delete-source" == action) engine.runDeleteSources(sourceTree ?: "")
        else if ("recycle-phone" == action) engine.runCleanup()
        else if ("abandon" == action) engine.runAbandon()
        else if (batch != null) engine.runImport(batch)
        else engine.runUpload(cellular)
        TransferEngine.outcome = "completed"
      } catch (e: Exception) {
        TransferEngine.outcome = if (e is TransferEngine.Paused) "paused" else "error"
        TransferEngine.message = TransferEngine.readable(e)
      } finally {
        if (wake.isHeld) wake.release()
        TransferEngine.BUSY.set(false)
        handler.post {
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
        }
      }
    }, "video-transfer")
    thread!!.start()
    handler.post(ticker)
    return START_NOT_STICKY
  }

  private val ticker = object : Runnable {
    override fun run() {
      if (thread != null && thread!!.isAlive) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTICE, notification(this@TransferService))
        handler.postDelayed(this, 1000)
      }
    }
  }

  override fun onTimeout(startId: Int, type: Int) {
    TransferEngine.stopped = true
    thread?.interrupt()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    handler.removeCallbacks(ticker)
    super.onDestroy()
  }

  override fun onBind(i: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL = "transfer"
    private const val NOTICE = 10

    @JvmStatic
    fun notification(c: Context): Notification {
      val manager = c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(
          NotificationChannel(CHANNEL, "视频传输", NotificationManager.IMPORTANCE_LOW))
      val open = PendingIntent.getActivity(
          c, 0, Intent(c, MainActivity::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val pause = PendingIntent.getService(
          c, 1, Intent(c, TransferService::class.java).setAction("pause"),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      return Notification.Builder(c, CHANNEL)
          .setSmallIcon(com.makewheels.dashcam.R.drawable.ic_notification)
          .setContentTitle(TransferEngine.message)
          .setContentText(TransferEngine.detail())
          .setContentIntent(open)
          .setOngoing(TransferEngine.BUSY.get())
          .addAction(Notification.Action.Builder(null, "暂停", pause).build())
          .build()
    }
  }
}
