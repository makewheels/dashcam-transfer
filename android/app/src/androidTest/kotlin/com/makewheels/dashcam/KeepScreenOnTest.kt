package com.makewheels.dashcam

import android.content.Intent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {
  val instrument = InstrumentationRegistry.getInstrumentation()

  private fun holding(activity: MainActivity): Boolean =
      (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

  @Test
  fun screenStaysOnWhileTaskRunsAndReleasesAfter() {
    val context = instrument.targetContext
    context.getSharedPreferences("settings", 0).edit()
        .putBoolean("intro_seen", true)
        .putLong("update_checked", System.currentTimeMillis())
        .putBoolean("upload_paused", true)
        .apply()
    val activity = instrument.startActivitySync(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
      instrument.runOnMainSync {
        assertEquals(false, holding(activity))
        activity.applyKeepScreenOn(true)
        assertEquals(true, holding(activity))
        activity.applyKeepScreenOn(false)
        assertEquals(false, holding(activity))
      }
    } finally {
      activity.finish()
    }
  }
}
