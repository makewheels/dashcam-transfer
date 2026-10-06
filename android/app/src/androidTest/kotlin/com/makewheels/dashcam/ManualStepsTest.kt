package com.makewheels.dashcam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualStepsTest {
  @Test
  fun copyingKeepsSourceEvenForLegacyAutoDeleteBatchAndDeleteRechecksPhone() {
    val c = InstrumentationRegistry.getInstrumentation().targetContext
    val r = c.contentResolver
    val store = Store(c)
    val id = "manual-" + UUID.randomUUID()
    val authority = c.packageName + ".fixtures"
    val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
    c.grantUriPermission(
        c.packageName, tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
    var src: Uri? = null
    var dst: Uri? = null
    val payload = "complete original video bytes".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
    try {
      src = DocumentsContract.createDocument(r, root, "video/mp4", "$id.mp4")
      assertNotNull(src)
      r.openOutputStream(src!!)!!.use { it.write(payload) }
      val v = ContentValues()
      v.put(MediaStore.Video.Media.DISPLAY_NAME, "$id.mp4")
      v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/行车视频测试/")
      dst = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
      assertNotNull(dst)
      r.openOutputStream(dst!!)!!.use { it.write(payload) }
      val hash = Digests.hash(ByteArrayInputStream(payload)) { }
      v.clear()
      v.put("id", id)
      v.put("tree", tree.toString())
      v.put("ready", 0)
      v.put("delete_source", 1)
      store.writableDatabase.insertOrThrow("batches", null, v)
      v.clear()
      v.put("id", id)
      v.put("batch", id)
      v.put("tree", tree.toString())
      v.put("source", src.toString())
      v.put("dest", dst.toString())
      v.put("name", "$id.mp4")
      v.put("size", payload.size)
      v.put("sha", hash[0])
      v.put("crc", hash[1])
      v.put("state", "waiting")
      store.writableDatabase.insertOrThrow("files", null, v)
      TransferEngine.stopped = false
      val engine = TransferEngine(c)
      engine.runImport(id)
      assertTrue(DocumentFile.fromSingleUri(c, src!!)!!.exists())
      assertEquals("ready", store.files("id=?", id)[0].state)
      r.openOutputStream(dst, "wt")!!.use { it.write("changed phone copy".toByteArray()) }
      val sourceReadsBeforeDelete = FixtureDocumentsProvider.readOpens.get()
      engine.runDeleteSources(tree.toString())
      assertTrue(DocumentFile.fromSingleUri(c, src!!)!!.exists())
      assertEquals(0, if (store.files("id=?", id)[0].sourceDeleted) 1 else 0)
      r.openOutputStream(dst, "wt")!!.use { it.write(payload) }
      engine.runDeleteSources(tree.toString())
      assertFalse(DocumentFile.fromSingleUri(c, src!!)!!.exists())
      assertEquals("Deleting must not reopen source video bytes", sourceReadsBeforeDelete, FixtureDocumentsProvider.readOpens.get())
      assertEquals("已处理 1 / 1 个视频", TransferEngine.detail())
    } finally {
      if (src != null && DocumentFile.fromSingleUri(c, src!!)!!.exists())
        DocumentsContract.deleteDocument(r, src)
      if (dst != null) r.delete(dst, null, null)
      store.writableDatabase.delete("files", "id=?", arrayOf(id))
      store.writableDatabase.delete("batches", "id=?", arrayOf(id))
      store.close()
    }
  }

  @Test
  fun directoryDisplayKeepsVolumeAndEveryPathSegment() {
    val c = InstrumentationRegistry.getInstrumentation().targetContext
    val path = SourcePath.display(
        c,
        DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "ABCD-1234:DCIM/recorder/video"))
    assertEquals("/storage/ABCD-1234/DCIM/recorder/video", path)
    val unknown = SourcePath.display(
        c, Uri.parse("content://other.provider/tree/whole%2Flocation"))
    assertTrue(unknown.contains("content://other.provider/tree/whole%2Flocation"))
    assertTrue(unknown.contains("未公开"))
  }
}
