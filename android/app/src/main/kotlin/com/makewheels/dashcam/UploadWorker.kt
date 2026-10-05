package com.makewheels.dashcam

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

class UploadWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
  private var ownsTask = false

  override fun doWork(): Result = Result.success()

  override fun onStopped() {
    if (ownsTask) TransferEngine.stopped = true
  }

  companion object {
    @JvmStatic
    fun schedule(c: Context) {
      // Cancel jobs scheduled by older releases. Every operation now needs a button press.
      WorkManager.getInstance(c).cancelUniqueWork("wifi-upload")
      WorkManager.getInstance(c).cancelUniqueWork("wifi-watch")
    }
  }
}
