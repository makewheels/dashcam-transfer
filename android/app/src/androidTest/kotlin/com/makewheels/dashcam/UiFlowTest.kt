package com.makewheels.dashcam

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
  val instrument = InstrumentationRegistry.getInstrumentation()

  fun shot(name: String) {
    instrument.waitForIdleSync()
    SystemClock.sleep(3500)
    val window = instrument.uiAutomation.rootInActiveWindow
    if (window != null) {
      for (node in window.findAccessibilityNodeInfosByText("稍后"))
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    instrument.waitForIdleSync()
    SystemClock.sleep(300)
    val bitmap = instrument.uiAutomation.takeScreenshot()
    assertNotNull(bitmap)
    FileOutputStream(File(instrument.targetContext.cacheDir, "$name.png")).use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    bitmap.recycle()
  }

  fun clickDialog(text: String) {
    val root = instrument.uiAutomation.rootInActiveWindow
    assertNotNull(root)
    for (node in root!!.findAccessibilityNodeInfosByText(text))
      if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
    fail("Action missing: $text")
  }

  fun contains(view: View, value: String): Boolean {
    if (view.visibility != View.VISIBLE) return false
    if (view is TextView && view.text.toString().contains(value)) return true
    if (view is ViewGroup) {
      for (i in 0 until view.childCount)
        if (contains(view.getChildAt(i), value)) return true
    }
    return false
  }

  @Test
  fun guidanceNavigationAndProgressHaveClearNextActions() {
    TransferEngine.message = "准备就绪"
    TransferEngine.taskIds = emptyList()
    TransferEngine.taskKind = ""
    TransferEngine.outcome = "idle"
    TransferEngine.phase = TransferProgress.Phase.IDLE
    val id = "ui-test-" + java.util.UUID.randomUUID()
    val context = instrument.targetContext
    context.getSharedPreferences("settings", 0).edit()
        .putBoolean("intro_seen", false)
        .putLong("update_checked", System.currentTimeMillis())
        .putBoolean("upload_paused", true)
        .apply()
    val activity = instrument.startActivitySync(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    try {
      instrument.runOnMainSync {
        assertTrue(contains(activity.root, "拷贝到手机"))
        assertFalse(contains(activity.root, "待上传"))
        assertTrue(contains(activity.root, "删除卡上已拷贝的视频"))
        assertFalse(contains(activity.root, "设置"))
        assertTrue(contains(activity.root, "TF 卡"))
      }
      SystemClock.sleep(3500)
      shot("home-status")
      instrument.runOnMainSync {
        activity.prefs.edit()
            .putString(
                "source_tree",
                android.provider.DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "ABCD-1234:DCIM/recorder/video").toString())
            .apply()
        activity.refresh()
        assertEquals(
            "/storage/ABCD-1234/DCIM/recorder/video", activity.source.text.toString())
      }
      shot("folder-path")
      val connected = activity.cardPresent
      instrument.runOnMainSync {
        activity.cardPresent = false
        activity.readerPresent = false
        activity.refresh()
      }
      shot("home-disconnected")
      instrument.runOnMainSync {
        activity.cardPresent = connected
        activity.refresh()
      }
      instrument.runOnMainSync { activity.selectTab(1) }
      instrument.waitForIdleSync()
      instrument.runOnMainSync { assertTrue(contains(activity.root, "去导入视频")) }
      shot("queue-empty")
      val file = ContentValues()
      file.put("id", id)
      file.put("batch", "ui-review")
      file.put("name", "20261004_090001_FRONT.mp4")
      file.put("size", 512L * 1024 * 1024)
      file.put("date", "2026-10-04")
      file.put("import_time", "2026-10-04_21-35-08")
      file.put("state", "copying")
      activity.store.writableDatabase.insertOrThrow("files", null, file)
      file.put("id", "$id-second")
      file.put("name", "20261004_090002_FRONT.mp4")
      activity.store.writableDatabase.insertOrThrow("files", null, file)
      context.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", false).apply()
      TransferEngine.BUSY.set(true)
      TransferEngine.stopped = false
      TransferEngine.taskKind = "import"
      TransferEngine.taskIds = listOf(id, "$id-second")
      TransferEngine.generation = 101
      TransferEngine.activeId = id
      TransferEngine.phase = TransferProgress.Phase.COPY
      TransferEngine.done = 192L * 1024 * 1024
      TransferEngine.total = 512L * 1024 * 1024
      TransferEngine.phaseBase = 0
      TransferEngine.started = SystemClock.elapsedRealtime() - 15000
      TransferEngine.message = "复制 1/1 · 20261004_090001_FRONT.mp4"
      instrument.runOnMainSync {
        activity.selectTab(0)
        (activity.pages[0] as android.widget.ScrollView).scrollTo(0, 2000)
        activity.progressWasVisible = false
        activity.batchEstimate.generation = Long.MIN_VALUE
        activity.batchEstimate.remaining(
            101, SystemClock.elapsedRealtime() - 1500, 0.0, 3.0 * 1024 * 1024 * 1024, true)
        activity.refresh()
      }
      instrument.waitForIdleSync()
      SystemClock.sleep(900)
      instrument.runOnMainSync {
        assertEquals(0, (activity.pages[0] as android.widget.ScrollView).scrollY)
        assertEquals("22%", activity.homeTask.percent.text.toString())
        assertTrue(activity.homeTask.totalEta.text.toString().startsWith("约 "))
        assertFalse(contains(activity.root, "当前阶段剩余"))
        assertFalse(contains(activity.root, "当前文件剩余"))
        val bounds = android.graphics.Rect()
        assertTrue(activity.homeTask.percent.getGlobalVisibleRect(bounds))
      }
      shot("import-progress")
      instrument.runOnMainSync {
        TransferEngine.taskKind = "delete-source"
        TransferEngine.taskIds = listOf(id, "$id-second")
        TransferEngine.done = 1
        TransferEngine.total = 2
        val deleted = ContentValues().apply { put("source_deleted", 1) }
        activity.store.update(id, deleted)
        activity.refresh()
        assertEquals("1 / 2", activity.homeTask.percent.text.toString())
        assertEquals("已删除 1 / 2 个视频", activity.homeTask.totalLabel.text.toString())
        assertEquals(android.view.View.GONE, activity.homeTask.speed.visibility)
        assertEquals(android.view.View.GONE, activity.homeTask.totalEta.visibility)
        assertFalse(TransferEngine.detail().contains("/s"))
      }
      shot("delete-file-progress")

      file.clear()
      file.put("state", "uploading")
      file.put("sha", "test")
      activity.store.update(id, file)
      TransferEngine.taskKind = "upload"
      TransferEngine.generation = 102
      TransferEngine.phase = TransferProgress.Phase.UPLOAD
      TransferEngine.message = "上传 · 20261004_090001_FRONT.mp4"
      instrument.runOnMainSync {
        activity.batchEstimate.remaining(
            102, SystemClock.elapsedRealtime() - 1500, 0.0, 1024.0 * 1024 * 1024, true)
        activity.selectTab(1)
        activity.refresh()
      }
      shot("upload-progress")
      TransferEngine.BUSY.set(false)
      context.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", true).apply()
      instrument.runOnMainSync { activity.selectTab(2) }
      instrument.waitForIdleSync()
      SystemClock.sleep(300)
      instrument.runOnMainSync {
        activity.batchesLoading = false
        activity.batchStatus!!.text = "本机导入记录 · 云端历史可联网同步"
        activity.renderBatches()
      }
      shot("batch-history")
      instrument.runOnMainSync {
        val batch = activity.BatchView()
        batch.id = "ui-review"
        batch.time = "2026-10-04_21-35-08"
        batch.local = activity.store.files("id=?", id)
        activity.showBatch(batch)
      }
      shot("batch-detail")
      instrument.runOnMainSync { activity.homePause!!.visibility = View.GONE }
      activity.prefs.edit().remove("source_tree").apply()
      activity.store.writableDatabase
          .delete("files", "id IN (?,?)", arrayOf(id, "$id-second"))
    } finally {
      activity.prefs.edit().remove("source_tree").apply()
      activity.store.writableDatabase
          .delete("files", "id IN (?,?)", arrayOf(id, "$id-second"))
      TransferEngine.BUSY.set(false)
      TransferEngine.taskIds = emptyList()
      TransferEngine.taskKind = ""
      TransferEngine.outcome = "idle"
      TransferEngine.activeId = ""
      instrument.runOnMainSync { activity.finish() }
    }
  }
}
