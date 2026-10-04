package com.makewheels.dashcam;

import android.app.*;
import android.content.*;
import android.os.*;

public final class TransferService extends Service {
  static final String CHANNEL = "transfer";
  static final int NOTICE = 10;
  final Handler handler = new Handler(Looper.getMainLooper());
  Thread thread;

  static Notification notification(Context c) {
    NotificationManager manager = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "视频传输", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            c,
            0,
            new Intent(c, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent pause =
        PendingIntent.getService(
            c,
            1,
            new Intent(c, TransferService.class).setAction("pause"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(c, CHANNEL)
        .setSmallIcon(com.makewheels.dashcam.R.drawable.ic_launcher)
        .setContentTitle(TransferEngine.message)
        .setContentText(TransferEngine.detail())
        .setContentIntent(open)
        .setOngoing(TransferEngine.BUSY.get())
        .addAction(new Notification.Action.Builder(null, "暂停", pause).build())
        .build();
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    if (i == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("pause".equals(i.getAction())) {
      TransferEngine.stop(this);
      if (thread == null) stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(NOTICE, notification(this));
    if (!TransferEngine.BUSY.compareAndSet(false, true)) {
      if (thread == null) stopSelf();
      return START_NOT_STICKY;
    }
    TransferEngine.stopped = false;
    TransferEngine.pauseReason = "已暂停，可继续";
    getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", false).apply();
    String batch = i.getStringExtra("batch");
    boolean cellular = i.getBooleanExtra("cellular", false);
    thread =
        new Thread(
            () -> {
              PowerManager.WakeLock wake =
                  ((PowerManager) getSystemService(POWER_SERVICE))
                      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transfer:active");
              wake.acquire(6 * 60 * 60 * 1000L);
              try {
                TransferEngine engine = new TransferEngine(this);
                if (batch != null) {
                  engine.runImport(batch);
                  if (engine.wifi()) engine.runUpload(false);
                } else engine.runUpload(cellular);
              } catch (Exception e) {
                TransferEngine.message = TransferEngine.readable(e);
              } finally {
                if (wake.isHeld()) wake.release();
                TransferEngine.BUSY.set(false);
                handler.post(
                    () -> {
                      stopForeground(STOP_FOREGROUND_REMOVE);
                      stopSelf();
                    });
              }
            },
            "video-transfer");
    thread.start();
    handler.post(ticker);
    return START_NOT_STICKY;
  }

  final Runnable ticker =
      new Runnable() {
        public void run() {
          if (thread != null && thread.isAlive()) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTICE, notification(TransferService.this));
            handler.postDelayed(this, 1000);
          }
        }
      };

  @Override
  public void onTimeout(int startId, int type) {
    TransferEngine.stopped = true;
    if (thread != null) thread.interrupt();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacks(ticker);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
