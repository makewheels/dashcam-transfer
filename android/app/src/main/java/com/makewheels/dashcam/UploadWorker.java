package com.makewheels.dashcam;

import android.content.*;
import androidx.work.*;

public final class UploadWorker extends Worker {
  volatile boolean ownsTask = false;

  public UploadWorker(Context c, WorkerParameters p) {
    super(c, p);
  }

  static void schedule(Context c) {
    // Cancel jobs scheduled by older releases. Every operation now needs a button press.
    WorkManager.getInstance(c).cancelUniqueWork("wifi-upload");
    WorkManager.getInstance(c).cancelUniqueWork("wifi-watch");
  }

  @Override
  public Result doWork() {
    return Result.success();
  }

  @Override
  public void onStopped() {
    if (ownsTask) TransferEngine.stopped = true;
  }
}
