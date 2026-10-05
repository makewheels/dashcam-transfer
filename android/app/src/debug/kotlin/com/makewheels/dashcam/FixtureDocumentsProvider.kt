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

  override fun onCreate(): Boolean = true

  override fun queryRoots(projection: Array<String>?): Cursor {
    val cursor = MatrixCursor(projection ?: COLUMNS)
    addRoot(cursor)
    return cursor
  }

  private fun addRoot(cursor: MatrixCursor) {
    val root = file("root")
    val row = cursor.newRow()
    for (column in cursor.columnNames) {
      when (column) {
        Document.COLUMN_DOCUMENT_ID -> row.add("root")
        Document.COLUMN_DISPLAY_NAME -> row.add("行车转存测试存储")
        Document.COLUMN_MIME_TYPE -> row.add(Document.MIME_TYPE_DIR)
        Document.COLUMN_FLAGS -> row.add(
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
                or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE)
        Document.COLUMN_SIZE -> row.add(root.totalSpace)
        Document.COLUMN_LAST_MODIFIED -> row.add(root.lastModified())
        else -> row.add(null)
      }
    }
  }

  override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
    val cursor = MatrixCursor(projection ?: COLUMNS)
    try {
      add(cursor, documentId)
    } catch (_: FileNotFoundException) {
    }
    return cursor
  }

  override fun queryChildDocuments(
      parentDocumentId: String, projection: Array<String>?, sortOrder: String?
  ): Cursor {
    val cursor = MatrixCursor(projection ?: COLUMNS)
    val parent = file(parentDocumentId)
    val children = parent.listFiles()
    if (children != null) for (child in children) add(cursor, child.getName()!!)
    return cursor
  }

  override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
    return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
  }

  override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
    var name = displayName
    if (mimeType == Document.MIME_TYPE_DIR && !name.endsWith(".dir")) name += ".dir"
    val target = File(file(parentDocumentId), name)
    if (mimeType != Document.MIME_TYPE_DIR) target.createNewFile() else target.mkdirs()
    return target.getName()!!
  }

  override fun deleteDocument(documentId: String) {
    if (!file(documentId).delete()) throw FileNotFoundException("delete failed")
  }

  override fun renameDocument(documentId: String, displayName: String): String {
    val source = file(documentId)
    val target = File(source.getParentFile(), displayName)
    if (!source.renameTo(target)) throw FileNotFoundException("rename failed")
    return target.getName()!!
  }

  companion object {
    val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED)
  }
}
