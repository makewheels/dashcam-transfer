package com.makewheels.dashcam

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardInventoryTest {
  @Test
  fun detectsFilesUpdatesAfterDeletionAndClearsOnDisconnect() {
    val i = InstrumentationRegistry.getInstrumentation()
    val c = i.targetContext
    val tree = DocumentsContract.buildTreeDocumentUri(c.packageName + ".fixtures", "root")
    c.grantUriPermission(
        c.packageName, tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    val file = DocumentsContract.createDocument(
        c.contentResolver,
        DocumentsContract.buildDocumentUriUsingTree(tree, "root"), "video/mp4",
        "inventory-" + UUID.randomUUID() + ".mp4")
    c.contentResolver.openOutputStream(file!!)!!.use { it.write(ByteArray(77777)) }
    c.getSharedPreferences("settings", 0).edit()
        .putString("source_tree", tree.toString())
        .putLong("update_checked", System.currentTimeMillis())
        .apply()
    val activity = i.startActivitySync(
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
      waitFor(activity, "1 个视频")
      shot("card-detected")
      i.runOnMainSync {
        assertTrue(activity.inventoryDialog == null)
        assertTrue(activity.inventorySummary.contains("76.0 KB"))
        activity.inspectCard(true)
      }
      SystemClock.sleep(500)
      i.runOnMainSync { assertTrue(activity.inventoryDialog == null) }
      DocumentsContract.deleteDocument(c.contentResolver, file)
      i.runOnMainSync { activity.inspectCard(true) }
      waitFor(activity, "0 个视频")
      shot("card-empty")
      i.runOnMainSync {
        assertTrue(activity.inventoryDialog == null)
        activity.cardPresent = false
        activity.clearInventory()
        activity.refresh()
        assertEquals("", activity.inventorySummary)
        assertTrue(activity.inventoryDialog == null)
      }
    } finally {
      try {
        DocumentsContract.deleteDocument(c.contentResolver, file)
      } catch (_: Exception) {
      }
      activity.prefs.edit().remove("source_tree").apply()
      i.runOnMainSync {
        activity.clearInventory()
        activity.finish()
      }
    }
  }

  private fun shot(name: String) {
    val i = InstrumentationRegistry.getInstrumentation()
    i.waitForIdleSync()
    SystemClock.sleep(300)
    val image = i.uiAutomation.takeScreenshot()
    FileOutputStream(File(i.targetContext.cacheDir, "$name.png")).use { out ->
      image.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    image.recycle()
  }

  private fun waitFor(activity: MainActivity, expected: String) {
    val deadline = SystemClock.elapsedRealtime() + 10000
    while (!activity.inventorySummary.contains(expected)
        && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      assertTrue(activity.inventorySummary, activity.inventorySummary.contains(expected))
    }
  }
}
