package com.makewheels.dashcam

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.View
import android.widget.ScrollView
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStream
import java.util.Collections
import java.util.Random
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyFlowTest {
  @Test
  fun copyButtonShowsProgressAndCompletionOffersSecondStep() {
    val i = InstrumentationRegistry.getInstrumentation()
    val c = i.targetContext
    val resolver = c.contentResolver
    val name = "copy-flow-" + UUID.randomUUID() + ".mp4"
    val tree = DocumentsContract.buildTreeDocumentUri(c.packageName + ".fixtures", "root")
    c.grantUriPermission(
        c.packageName, tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    val source = DocumentsContract.createDocument(
        resolver, DocumentsContract.buildDocumentUriUsingTree(tree, "root"), "video/mp4", name)
    val payload = ByteArray(65536)
    Random(1).nextBytes(payload)
    resolver.openOutputStream(source!!)!!.use { it.write(payload) }
    TransferEngine.taskIds = Collections.emptyList()
    TransferEngine.taskKind = ""
    TransferEngine.outcome = "idle"
    TransferEngine.BUSY.set(false)
    c.getSharedPreferences("settings", 0).edit()
        .putString("source_tree", tree.toString())
        .putLong("update_checked", System.currentTimeMillis())
        .apply()
    val activity = i.startActivitySync(
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
      val inventoryDeadline = SystemClock.elapsedRealtime() + 10000
      while (activity.inventorySignature.isEmpty()
          && SystemClock.elapsedRealtime() < inventoryDeadline) SystemClock.sleep(100)
      i.runOnMainSync {
        (activity.pages[0] as ScrollView).scrollTo(0, 2000)
        activity.inventoryDialog?.dismiss()
        activity.copyButton.performClick()
      }
      val deadline = SystemClock.elapsedRealtime() + 20000
      while (("completed" != TransferEngine.outcome || TransferEngine.BUSY.get())
          && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
      assertEquals("completed", TransferEngine.outcome)
      assertTrue(
          "copy unexpectedly deleted source", DocumentFile.fromSingleUri(c, source!!)!!.exists())
      val files = activity.store.files("name=?", name)
      assertEquals(1, files.size)
      assertEquals("ready", files[0].state)
      i.runOnMainSync { activity.refresh() }
      i.waitForIdleSync()
      SystemClock.sleep(900)
      i.runOnMainSync {
        assertEquals(0, (activity.pages[0] as ScrollView).scrollY)
        assertEquals(View.VISIBLE, activity.homeTask.card.visibility)
        assertEquals("100%", activity.homeTask.percent.text.toString())
        assertTrue(activity.homeTask.action.text.toString().contains("下一步"))
        assertTrue(activity.liveHint.text.toString().contains("第一步已完成"))
      }
      UiFlowTest().shot("copy-complete")
      i.runOnMainSync { activity.homeTask.action.performClick() }
      assertEquals(1, activity.tab)
      assertEquals("② 上传云端", activity.secondStep.text.toString())
    } finally {
      TransferEngine.stopped = true
      for (file in activity.store.files("name=?", name)) {
        if (file.dest != null) {
          if (file.dest!!.startsWith("content:"))
            resolver.delete(Uri.parse(file.dest), null, null)
          else java.io.File(file.dest!!).delete()
        }
        activity.store.writableDatabase.delete("files", "id=?", arrayOf(file.id))
        activity.store.writableDatabase.delete("batches", "id=?", arrayOf(file.batch))
      }
      if (DocumentFile.fromSingleUri(c, source!!)!!.exists())
        DocumentsContract.deleteDocument(resolver, source)
      activity.prefs.edit().remove("source_tree").apply()
      TransferEngine.BUSY.set(false)
      TransferEngine.taskIds = Collections.emptyList()
      TransferEngine.taskKind = ""
      TransferEngine.outcome = "idle"
      i.runOnMainSync { activity.finish() }
    }
  }
}
