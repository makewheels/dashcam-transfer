package com.makewheels.dashcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecycleTest {
  @Test
  fun verifiedUploadMovesToTrashAndBytesAreRecoverable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = context.contentResolver
    val store = Store(context)
    val bytes = "recycle test video bytes".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    val id = "recycle-test-" + java.util.UUID.randomUUID()
    var uri: Uri? = null
    try {
      val values = ContentValues()
      values.put(MediaStore.Video.Media.DISPLAY_NAME, "$id.mp4")
      values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/行车视频测试/")
      values.put(MediaStore.Video.Media.IS_PENDING, 1)
      uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
      assertNotNull(uri)
      resolver.openOutputStream(uri!!)!!.use { it.write(bytes) }
      values.clear()
      values.put(MediaStore.Video.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      val digest = Digests.hash(ByteArrayInputStream(bytes)) { }
      values.clear()
      values.put("id", id)
      values.put("dest", uri.toString())
      values.put("size", bytes.size)
      values.put("name", "$id.mp4")
      values.put("sha", digest[0])
      values.put("crc", digest[1])
      values.put("state", "cleanup_error")
      store.writableDatabase.insertOrThrow("files", null, values)
      TransferEngine.stopped = false
      val engine = TransferEngine(context)
      engine.runUpload(false)
      assertEquals("cleanup_error", store.files("id=?", id)[0].state)
      resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), null, null, null).use { before ->
        assertNotNull(before)
        assertTrue(before!!.moveToFirst())
        assertEquals(0, before.getInt(0))
      }
      engine.runCleanup()
      if (Build.VERSION.SDK_INT >= 30) {
        assertEquals("uploaded", store.files("id=?", id)[0].state)
        val query = Bundle()
        query.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), query, null).use { cursor ->
          assertNotNull(cursor)
          assertTrue(cursor!!.moveToFirst())
          assertEquals(1, cursor.getInt(0))
        }
      } else assertEquals("cleanup_error", store.files("id=?", id)[0].state)
      resolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
    } finally {
      // Only this test's freshly created object is removed; no user media is queried.
      if (uri != null) resolver.delete(uri, null, null)
      store.writableDatabase.delete("files", "id=?", arrayOf(id))
      store.close()
    }
  }
}
