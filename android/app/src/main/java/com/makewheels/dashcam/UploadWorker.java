package com.makewheels.dashcam;

import android.content.*;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public final class UploadWorker extends Worker {
  volatile boolean ownsTask = false;

  public UploadWorker(Context c, WorkerParameters p) {
    super(c, p);
  }

  static void schedule(Context c) {
    Constraints constraints =
        new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    WorkManager.getInstance(c)
        .enqueueUniqueWork(
            "wifi-upload",
            ExistingWorkPolicy.KEEP,
            new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build());
    WorkManager.getInstance(c)
        .enqueueUniquePeriodicWork(
            "wifi-watch",
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(UploadWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build());
  }

  @Override
  public Result doWork() {
    TransferEngine engine = new TransferEngine(getApplicationContext());
    if (engine.prefs.getBoolean("upload_paused", false)) return Result.success();
    if (!engine.wifi()) return Result.retry();
    if (!TransferEngine.BUSY.compareAndSet(false, true)) return Result.retry();
    ownsTask = true;
    TransferEngine.stopped = false;
    try {
      setForegroundAsync(
              new ForegroundInfo(
                  11,
                  TransferService.notification(getApplicationContext()),
                  android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
          .get();
      engine.runUpload(false);
      return Result.success();
    } catch (Exception e) {
      TransferEngine.message = TransferEngine.readable(e);
      return Result.retry();
    } finally {
      ownsTask = false;
      TransferEngine.BUSY.set(false);
    }
  }

  @Override
  public void onStopped() {
    if (ownsTask) TransferEngine.stopped = true;
  }
}
