package com.makewheels.dashcam

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.ArrayList

internal class Store(c: Context) : SQLiteOpenHelper(c, "transfers.db", null, 2) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE files(id TEXT PRIMARY KEY,batch TEXT,source TEXT,tree TEXT,name TEXT,size" +
            " INTEGER,modified INTEGER,dest TEXT,offset INTEGER DEFAULT 0,state TEXT DEFAULT" +
            " 'waiting',sha TEXT,crc TEXT,date TEXT,error TEXT DEFAULT '',source_deleted INTEGER" +
            " DEFAULT 0,import_time TEXT)")
    db.execSQL(
        "CREATE TABLE batches(id TEXT PRIMARY KEY,tree TEXT,delete_source INTEGER,ready INTEGER" +
            " DEFAULT 0)")
  }

  override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {
    if (a < 2) db.execSQL("ALTER TABLE files ADD COLUMN import_time TEXT")
  }

  fun pending(): ArrayList<Item> = files(PENDING_QUERY)

  companion object {
    const val PENDING_QUERY = "state IN ('waiting','copying','ready','copy_error','uploading','upload_error','abandon_error')"
    fun isPending(state: String): Boolean = state in listOf(
        "waiting", "copying", "ready", "copy_error", "uploading", "upload_error", "abandon_error")
  }

  @Synchronized
  fun update(id: String, v: ContentValues) {
    writableDatabase.update("files", v, "id=?", arrayOf(id))
  }

  @Synchronized
  fun state(id: String, state: String, error: String) {
    val v = ContentValues()
    v.put("state", state)
    v.put("error", error)
    update(id, v)
  }

  @Synchronized
  fun delete(id: String) {
    writableDatabase.delete("files", "id=?", arrayOf(id))
  }

  @Synchronized
  fun deleteBatch(id: String) {
    writableDatabase.delete("batches", "id=?", arrayOf(id))
  }

  @Synchronized
  fun files(query: String, vararg args: String): ArrayList<Item> {
    val out = ArrayList<Item>()
    readableDatabase.query("files", null, query, args, null, null, "rowid ASC").use { c ->
      while (c.moveToNext()) out.add(Item(c))
    }
    return out
  }

  class Item(c: Cursor) {
    val id: String = s(c, "id") ?: ""
    val batch: String? = s(c, "batch")
    val source: String? = s(c, "source")
    val tree: String? = s(c, "tree")
    val name: String = s(c, "name") ?: ""
    var dest: String? = s(c, "dest")
    var state: String = s(c, "state") ?: ""
    val sha: String? = s(c, "sha")
    val crc: String? = s(c, "crc")
    val date: String? = s(c, "date")
    var error: String? = s(c, "error")
    val importTime: String? = s(c, "import_time")
    val size: Long = l(c, "size")
    var offset: Long = l(c, "offset")
    val modified: Long = l(c, "modified")
    val sourceDeleted: Boolean = l(c, "source_deleted") != 0L

    companion object {
      private fun s(c: Cursor, key: String): String? =
          c.getString(c.getColumnIndexOrThrow(key))

      private fun l(c: Cursor, key: String): Long =
          c.getLong(c.getColumnIndexOrThrow(key))
    }
  }
}
