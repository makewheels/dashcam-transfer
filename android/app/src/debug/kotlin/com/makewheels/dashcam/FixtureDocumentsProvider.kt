package com.makewheels.dashcam

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Isolated debug-only document storage for explicit deletion boundary tests. */
class FixtureDocumentsProvider : DocumentsProvider() {
  @Throws(FileNotFoundException::class)
  private fun file(id: String): File {
    val root = File(getContext()!!.cacheDir, "transfer-documents")
    root.mkdirs()
    if (id == "root") return root
    if (!id.matches(Regex("[a-zA-Z0-9._-]+")) || id == ".." || id == ".")
      throw FileNotFoundException()
    return File(root, id)
  }

  @Throws(FileNotFoundException::class)
  private fun add(cursor: MatrixCursor, id: String) {
    val f = file(id)
    if (!f.exists()) return
    val row = cursor.newRow()
    for (column in cursor.columnNames) {
      when (column) {
        Document.COLUMN_DOCUMENT_ID -> row.add(id)
        Document.COLUMN_DISPLAY_NAME -> row.add(f.getName())
        Document.COLUMN_MIME_TYPE ->
          row.add(if (f.isDirectory) Document.MIME_TYPE_DIR else "video/mp4")
        Document.COLUMN_FLAGS ->
          row.add(
              if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE
              else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
        Document.COLUMN_SIZE -> row.add(f.length())
        Document.COLUMN_LAST_MODIFIED -> row.add(f.lastModified())
        else -> row.add(null)
      }
    }
  }

  override fun isChildDocument(parent: String, child: String): Boolean {
    return try {
      parent == "root" && child != "root" && file(child).exists()
    } catch (_: FileNotFoundException) {
      false
    }
  }

  override fun onCreate(): Boolean = true

  override fun queryRoots(projection: Array<String>?): Cursor {
    return MatrixCursor(arrayOf("root_id"))
  }

  @Throws(FileNotFoundException::class)
  override fun queryDocument(id: String, projection: Array<String>?): Cursor {
    val cursor = MatrixCursor(projection ?: COLUMNS)
    add(cursor, id)
    return cursor
  }

  @Throws(FileNotFoundException::class)
  override fun queryChildDocuments(parent: String, projection: Array<String>?, sort: String?): Cursor {
    val cursor = MatrixCursor(projection ?: COLUMNS)
    val children = file(parent).listFiles()
    if (children != null) for (child in children) add(cursor, child.getName()!!)
    return cursor
  }

  @Throws(FileNotFoundException::class)
  override fun openDocument(id: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
    if (mode == "r") readOpens.incrementAndGet()
    return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode))
  }

  @Throws(FileNotFoundException::class)
  override fun createDocument(parent: String, mime: String, name: String): String {
    if (parent != "root") throw FileNotFoundException()
    return try {
      if (!file(name).createNewFile()) throw FileNotFoundException()
      name
    } catch (_: java.io.IOException) {
      throw FileNotFoundException()
    }
  }

  @Throws(FileNotFoundException::class)
  override fun deleteDocument(id: String) {
    if (id == "root" || !file(id).delete()) throw FileNotFoundException()
  }

  companion object {
    val readOpens = java.util.concurrent.atomic.AtomicInteger()
    val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED)
  }
}
