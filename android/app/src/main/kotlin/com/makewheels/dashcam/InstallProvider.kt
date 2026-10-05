package com.makewheels.dashcam

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class InstallProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  @Throws(FileNotFoundException::class)
  private fun file(u: Uri): File {
    if ("/update.apk" != u.path) throw FileNotFoundException()
    return File(getContext()!!.cacheDir, "update.apk")
  }

  override fun openFile(u: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw FileNotFoundException()
    return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun getType(u: Uri): String = "application/vnd.android.package-archive"

  override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, sort: String?): Cursor? {
    return try {
      val f = file(u)
      MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf<Any>("行车记录仪转存.apk", f.length()))
      }
    } catch (_: Exception) {
      null
    }
  }

  override fun insert(u: Uri, v: ContentValues?): Uri = throw UnsupportedOperationException()

  override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
      throw UnsupportedOperationException()

  override fun delete(u: Uri, s: String?, a: Array<String>?): Int =
      throw UnsupportedOperationException()
}
