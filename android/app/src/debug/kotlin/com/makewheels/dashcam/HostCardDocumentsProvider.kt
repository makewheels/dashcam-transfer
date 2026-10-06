package com.makewheels.dashcam

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/** Debug-only, read-only host-card bridge. Never included in the release APK. */
class HostCardDocumentsProvider : DocumentsProvider() {
  private val base = "http://10.0.2.2:8765"
  private var cached = JSONArray()
  private var checked = 0L
  @Synchronized private fun entries(): JSONArray {
    if (System.currentTimeMillis() - checked < 1000) return cached
    val connection = URL("$base/entries").openConnection() as HttpURLConnection
    connection.connectTimeout = 3000
    connection.readTimeout = 10000
    try {
      cached = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
      checked = System.currentTimeMillis()
      return cached
    } finally { connection.disconnect() }
  }
  private fun add(cursor: MatrixCursor, id: String) {
    val data = if (id == "root") null else {
      val list = entries()
      (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("name") == id }
          ?: throw FileNotFoundException()
    }
    val row = cursor.newRow()
    for (column in cursor.columnNames) row.add(when (column) {
      Document.COLUMN_DOCUMENT_ID -> id
      Document.COLUMN_DISPLAY_NAME -> if (id == "root") "TF card (read-only host bridge)" else id
      Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "video/*"
      Document.COLUMN_SIZE -> data?.getLong("size") ?: 0L
      Document.COLUMN_LAST_MODIFIED -> data?.getLong("modified") ?: 0L
      Document.COLUMN_FLAGS -> 0
      else -> null
    })
  }
  override fun onCreate() = true
  override fun isChildDocument(parent: String, child: String) = parent == "root" && child != "root"
  override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(arrayOf("root_id"))
  override fun queryDocument(id: String, projection: Array<String>?): Cursor =
      MatrixCursor(projection ?: columns).also { add(it, id) }
  override fun queryChildDocuments(parent: String, projection: Array<String>?, sort: String?): Cursor {
    if (parent != "root") throw FileNotFoundException()
    val list = entries()
    return MatrixCursor(projection ?: columns).also { cursor ->
      for (index in 0 until list.length()) add(cursor, list.getJSONObject(index).getString("name"))
    }
  }
  override fun openDocument(id: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
    if (mode != "r" || id == "root") throw FileNotFoundException("Read-only source")
    val pipes = ParcelFileDescriptor.createReliablePipe()
    Thread {
      val connection = URL("$base/file/" + URLEncoder.encode(id, "UTF-8")).openConnection() as HttpURLConnection
      connection.connectTimeout = 3000
      connection.readTimeout = 15000
      try {
        connection.inputStream.use { input ->
          ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output -> input.copyTo(output, 1024 * 1024) }
        }
      } catch (error: Exception) {
        try { pipes[1].closeWithError(error.javaClass.simpleName) } catch (_: Exception) { }
      } finally { connection.disconnect() }
    }.start()
    return pipes[0]
  }
  private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
}
