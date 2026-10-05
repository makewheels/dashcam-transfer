package com.makewheels.dashcam

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

class TransferApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !getSharedPreferences("settings", 0).getBoolean("upload_paused", false)
            && !TransferEngine.BUSY.get()) {
          val constraints = Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .build()
          WorkManager.getInstance(this@TransferApplication)
              .enqueueUniqueWork(
                  "wifi-arrived",
                  ExistingWorkPolicy.KEEP,
                  OneTimeWorkRequest.Builder(UploadWorker::class.java)
                      .setConstraints(constraints)
                      .build())
        }
      }
    })
  }
}
