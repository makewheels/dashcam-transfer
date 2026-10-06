package com.makewheels.dashcam

import android.content.ContentValues
import android.content.Intent
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

private fun hostActivity(): MainActivity {
  val i = InstrumentationRegistry.getInstrumentation()
  val c = i.targetContext
  assumeTrue(InstrumentationRegistry.getArguments().getString("hostCardCheck") == "true")
  val tree = DocumentsContract.buildTreeDocumentUri(c.packageName + ".hostcard", "root")
  c.grantUriPermission(c.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
  c.getSharedPreferences("settings", 0).edit().putString("source_tree", tree.toString()).apply()
  TransferEngine.taskIds = emptyList()
  TransferEngine.taskKind = ""
  TransferEngine.outcome = "idle"
  TransferEngine.BUSY.set(false)
  val activity = i.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val deadline = SystemClock.elapsedRealtime() + 15000
  while (activity.startupChecking && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
  assertFalse(activity.startupChecking)
  return activity
}

class HostCardBaselineTest {
  @Test fun reproducesInvisibleWholeCardPreparation() {
    val activity = hostActivity()
    val i = InstrumentationRegistry.getInstrumentation()
    i.runOnMainSync { activity.scan() }
    SystemClock.sleep(10000)
    i.runOnMainSync {
      activity.refresh()
      assertTrue("Old preparation should still be reading the real card: " + TransferEngine.message, activity.scanning)
      assertFalse(TransferEngine.BUSY.get())
      assertEquals("准备中", activity.homeTask.phaseLabel.text.toString())
      println("BASELINE: after 10 seconds still preparing; no foreground progress")
    }
    UiFlowTest().shot("host-card-baseline")
  }
}

class HostCardFlowTest {
  @Test fun realCardStartsProgressPausesCopiesAndSkipsDuplicate() {
    val activity = hostActivity()
    val i = InstrumentationRegistry.getInstrumentation()
    val engine = TransferEngine(i.targetContext)
    fun waitFor(timeout: Long, predicate: () -> Boolean) {
      val deadline = SystemClock.elapsedRealtime() + timeout
      while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
      assertTrue("Timed out: " + TransferEngine.message, predicate())
    }
    try {
      val started = SystemClock.elapsedRealtime()
      i.runOnMainSync { activity.scan() }
      waitFor(15000) { TransferEngine.BUSY.get() && TransferEngine.phase.name == "DEDUP" && TransferEngine.done > 0 }
      val firstProgress = SystemClock.elapsedRealtime() - started
      val batchFiles = activity.store.files("1=1")
      assertTrue("Use the actual large card dataset", batchFiles.size >= 20)
      assertTrue(batchFiles.sumOf { it.size } > 1024L * 1024 * 1024)
      assertFalse("Whole-card prehash must not block preparation", activity.scanning)
      i.runOnMainSync {
        activity.refresh()
        assertTrue(activity.homeTask.phaseLabel.text.toString().contains("检查重复"))
        assertTrue(activity.homeTask.currentLabel.text.toString().isNotEmpty())
      }
      println("REAL_CARD: first visible progress ${firstProgress}ms; ${batchFiles.size} videos; ${batchFiles.sumOf { it.size }} bytes")
      UiFlowTest().shot("host-card-progress")
      val pausedAt = SystemClock.elapsedRealtime()
      TransferEngine.stop(i.targetContext)
      waitFor(10000) { !TransferEngine.BUSY.get() && TransferEngine.outcome == "paused" }
      println("REAL_CARD: pause acknowledged ${SystemClock.elapsedRealtime() - pausedAt}ms")
      i.runOnMainSync { activity.scan() }
      waitFor(150000) { activity.store.files("state='ready'").isNotEmpty() }
      TransferEngine.stop(i.targetContext)
      waitFor(15000) { !TransferEngine.BUSY.get() }
      val largest = batchFiles.maxBy { it.size }
      val copied = engine.store.files("source=? AND state='ready'", largest.source!!).firstOrNull() ?: run {
        val largeBatch = UUID.randomUUID().toString()
        engine.store.writableDatabase.insertOrThrow("batches", null, ContentValues().apply {
          put("id", largeBatch); put("tree", largest.tree)
        })
        engine.store.writableDatabase.insertOrThrow("files", null, ContentValues().apply {
          put("id", UUID.randomUUID().toString()); put("batch", largeBatch)
          put("source", largest.source); put("tree", largest.tree); put("name", largest.name)
          put("size", largest.size); put("modified", largest.modified); put("state", "waiting")
        })
        TransferEngine.stopped = false
        engine.runImport(largeBatch)
        engine.store.files("batch=?", largeBatch).single()
      }
      assertEquals(copied.size, File(copied.dest!!).length())
      assertFalse(copied.sourceDeleted)
      val hash = java.security.MessageDigest.getInstance("SHA-256")
      i.targetContext.contentResolver.openInputStream(android.net.Uri.parse(copied.source!!))!!.use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
      }
      assertEquals(copied.sha, Digests.hex(hash.digest()))
      val duplicateBatch = UUID.randomUUID().toString()
      engine.store.writableDatabase.insertOrThrow("batches", null, ContentValues().apply {
        put("id", duplicateBatch); put("tree", copied.tree)
      })
      engine.store.writableDatabase.insertOrThrow("files", null, ContentValues().apply {
        put("id", UUID.randomUUID().toString()); put("batch", duplicateBatch)
        put("source", copied.source); put("tree", copied.tree); put("name", copied.name)
        put("size", copied.size); put("modified", copied.modified); put("state", "waiting")
      })
      TransferEngine.stopped = false
      engine.runImport(duplicateBatch)
      assertTrue(TransferEngine.message.contains("已跳过 1 个"))
      assertTrue(engine.store.files("batch=?", duplicateBatch).isEmpty())
      assertTrue(File(copied.dest!!).exists())
      println("REAL_CARD: ${copied.size} byte video copied and independently SHA-256 verified; repeat copy skipped")
    } finally {
      TransferEngine.stop(i.targetContext)
      val deadline = SystemClock.elapsedRealtime() + 15000
      while (TransferEngine.BUSY.get() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
      if (!TransferEngine.BUSY.get()) {
        TransferEngine.stopped = false
        engine.runAbandon()
      }
      activity.prefs.edit().remove("source_tree").apply()
      i.runOnMainSync { activity.finish() }
    }
  }
}
