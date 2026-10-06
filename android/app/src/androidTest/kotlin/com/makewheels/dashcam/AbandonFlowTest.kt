package com.makewheels.dashcam

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AbandonFlowTest {
  @Test fun failedDeletionCanBeRetriedWithoutLosingTheRecord() {
    val engine = TransferEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    val id = UUID.randomUUID().toString()
    val directory = File(engine.context.cacheDir, id).apply { mkdirs() }
    val child = File(directory, "child").apply { writeText("remaining") }
    try {
      engine.store.writableDatabase.insertOrThrow("files", null, ContentValues().apply {
        put("id", id); put("state", "copying"); put("dest", directory.absolutePath)
      })
      TransferEngine.stopped = false
      try {
        engine.runAbandon()
        fail("Must report unsuccessful deletion")
      } catch (_: java.io.IOException) { }
      assertEquals("abandon_error", engine.store.files("id=?", id).single().state)
      assertTrue(child.exists())
      child.delete()
      engine.runAbandon()
      assertTrue(engine.store.files("id=?", id).isEmpty())
    } finally {
      engine.store.delete(id)
      directory.deleteRecursively()
    }
  }

  @Test fun removesPartialAndWaitingCopiesAndClearsProgress() {
    val engine = TransferEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    val batch = UUID.randomUUID().toString()
    val directory = File(engine.context.cacheDir, batch).apply { mkdirs() }
    try {
      engine.store.writableDatabase.insertOrThrow("batches", null, ContentValues().apply { put("id", batch) })
      for (state in listOf("waiting", "copying", "verifying", "ready", "copy_error", "uploading", "upload_error", "abandon_error")) {
        val file = File(directory, state).apply { writeText("partial") }
        engine.store.writableDatabase.insertOrThrow("files", null, ContentValues().apply {
          put("id", "$batch-$state"); put("batch", batch); put("state", state)
          put("dest", file.absolutePath); put("size", 100); put("offset", 7)
        })
      }
      TransferEngine.stopped = false
      engine.runAbandon()
      assertTrue(engine.store.files("batch=?", batch).isEmpty())
      assertFalse(directory.exists())
      assertTrue(TransferEngine.taskIds.isEmpty())
      assertEquals(0L, TransferEngine.done)
      assertEquals(0L, TransferEngine.total)
      engine.runAbandon()
      assertTrue(TransferEngine.taskIds.isEmpty())
    } finally {
      for (item in engine.store.files("batch=?", batch)) engine.store.delete(item.id)
      engine.store.deleteBatch(batch)
      directory.deleteRecursively()
    }
  }
}
