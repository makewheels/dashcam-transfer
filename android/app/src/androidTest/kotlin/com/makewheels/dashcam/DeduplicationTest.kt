package com.makewheels.dashcam

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DeduplicationTest {
  @Test fun consolidatesVerifiedOldCopiesAndRetainsChangedContent() {
    val engine = TransferEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    val prefix = UUID.randomUUID().toString()
    val directory = File(engine.context.cacheDir, prefix).apply { mkdirs() }
    val originals = listOf(File(directory, "first"), File(directory, "second"), File(directory, "changed"))
    val payload = byteArrayOf(1, 7, 9)
    originals.forEach { it.writeBytes(payload) }
    val hash = originals[0].inputStream().use { Digests.hash(it) { } }
    originals[2].writeBytes(byteArrayOf(9, 7, 1))
    try {
      originals.forEachIndexed { index, file ->
        engine.store.writableDatabase.insertOrThrow("files", null, ContentValues().apply {
          put("id", "$prefix-$index"); put("sha", hash[0]); put("crc", hash[1])
          put("size", 3); put("state", "ready"); put("dest", file.absolutePath)
        })
      }
      assertTrue(engine.hasLocalCopy(hash[0], 3))
      assertTrue(originals[0].exists())
      assertFalse(originals[1].exists())
      assertTrue("Changed content must remain available for repair", originals[2].exists())
      assertEquals(2, engine.store.files("id LIKE ?", "$prefix-%").size)
    } finally {
      for (item in engine.store.files("id LIKE ?", "$prefix-%")) engine.store.delete(item.id)
      directory.deleteRecursively()
    }
  }
}
