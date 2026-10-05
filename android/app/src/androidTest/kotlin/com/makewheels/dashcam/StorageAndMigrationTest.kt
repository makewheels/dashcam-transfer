package com.makewheels.dashcam

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageAndMigrationTest {
  @Test
  fun upgradeKeepsExistingQueueAndResumeOffset() {
    val db = SQLiteDatabase.create(null)
    val helper = Store(InstrumentationRegistry.getInstrumentation().targetContext)
    try {
      db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,state TEXT,offset INTEGER)")
      db.execSQL("INSERT INTO files VALUES('existing','copying',8388608)")
      helper.onUpgrade(db, 1, 2)
      db.rawQuery("SELECT state,offset,import_time FROM files WHERE id='existing'", null).use { c ->
        assertTrue(c.moveToFirst())
        assertEquals("copying", c.getString(0))
        assertEquals(8388608L, c.getLong(1))
        assertTrue(c.isNull(2))
      }
    } finally {
      db.close()
      helper.close()
    }
  }

  private fun shell(i: Instrumentation, command: String): String {
    i.uiAutomation.executeShellCommand(command).use { fd ->
      FileInputStream(fd.fileDescriptor).use { input ->
        val out = ByteArrayOutputStream()
        val b = ByteArray(4096)
        while (true) {
          val n = input.read(b)
          if (n == -1) break
          out.write(b, 0, n)
        }
        return out.toString("UTF-8")
      }
    }
  }

  @Test
  fun removableStorageMountAndRemovalUpdateGuidance() {
    val i = InstrumentationRegistry.getInstrumentation()
    val c = i.targetContext
    val manager = c.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    var virtual: android.os.storage.StorageVolume? = null
    for (v in manager.storageVolumes) {
      if (v.isRemovable && v.getDescription(c).contains("Virtual SD")) virtual = v
    }
    Assume.assumeNotNull(virtual)
    val uuid = virtual!!.uuid
    var volumeId: String? = null
    for (line in shell(i, "sm list-volumes public").split("\n")) {
      val fields = line.trim().split(" +".toRegex())
      if (fields.size >= 3 && fields[2] == uuid) volumeId = fields[0]
    }
    assertNotNull(volumeId)
    assertTrue(volumeId!!.startsWith("public:"))
    c.getSharedPreferences("settings", 0).edit()
        .putBoolean("intro_seen", true)
        .putBoolean("upload_paused", true)
        .putLong("update_checked", System.currentTimeMillis())
        .apply()
    val activity = i.startActivitySync(
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        as MainActivity
    try {
      assertTrue(activity.cardPresent)
      shell(i, "sm unmount $volumeId")
      var deadline = SystemClock.elapsedRealtime() + 8000
      while (activity.cardPresent && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
      assertFalse("Removal notification missing", activity.cardPresent)
      shell(i, "sm mount $volumeId")
      deadline = SystemClock.elapsedRealtime() + 8000
      while (!activity.cardPresent && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
      assertTrue("Mount notification missing", activity.cardPresent)
      i.runOnMainSync {
        assertTrue(activity.otgState!!.text.toString().contains("TF 卡已检测到"))
      }
    } finally {
      shell(i, "sm mount $volumeId")
      i.runOnMainSync { activity.finish() }
    }
  }
}
