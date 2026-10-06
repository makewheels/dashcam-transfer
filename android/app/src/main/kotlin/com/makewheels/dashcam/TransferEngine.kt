package com.makewheels.dashcam

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class TransferEngine(c: Context) {
  val context: Context = c.applicationContext
  val store = Store(context)
  val prefs = context.getSharedPreferences("settings", 0)
  private var manualCellular = false

  fun begin(kind: String, items: List<Store.Item>) {
    taskKind = kind
    generation = SystemClock.elapsedRealtimeNanos()
    outcome = "running"
    val ids = ArrayList<String>()
    for (item in items) ids.add(item.id)
    taskIds = Collections.unmodifiableList(ids)
    activeId = ""
    done = 0
    total = 0
    phase = TransferProgress.Phase.IDLE
    message = when (kind) {
      "import" -> "准备拷贝"
      "delete-source" -> "准备核对并删除卡上视频"
      "abandon" -> "准备放弃上次任务"
      "cleanup" -> "准备删除手机副本"
      else -> "准备上传"
    }
  }

  fun wifi(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.getNetworkCapabilities(cm.activeNetwork)
    return n != null && n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  fun connected(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.getNetworkCapabilities(cm.activeNetwork)
    return n != null && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  @Throws(Exception::class)
  fun check(uploading: Boolean) {
    if (stopped || Thread.currentThread().isInterrupted) throw Paused()
    if (uploading && (!connected() || (!wifi() && !manualCellular))) throw Paused()
  }

  fun progress(id: String, phase: String, current: Long, max: Long) {
    if (id != activeId || phase != message) {
      started = SystemClock.elapsedRealtime()
      phaseBase = current
    }
    activeId = id
    message = phase
    TransferEngine.phase = TransferProgress.detect(phase)
    done = current
    total = max
  }

  /** New copies live in app-private storage; MediaStore copies from older versions stay URI-backed. */
  fun openDestFd(dest: String, write: Boolean): ParcelFileDescriptor {
    if (destIsUri(dest)) {
      return context.contentResolver.openFileDescriptor(
          Uri.parse(dest), if (write) "rw" else "r")!!
    }
    val file = File(dest)
    file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
    return ParcelFileDescriptor.open(
        file,
        if (write) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        else ParcelFileDescriptor.MODE_READ_ONLY)
  }

  fun openDestInput(dest: String): InputStream {
    return if (destIsUri(dest)) context.contentResolver.openInputStream(Uri.parse(dest))!!
    else FileInputStream(File(dest))
  }

  /** Delete a finished local copy: app-private files unlink directly, legacy MediaStore copies go to trash. */
  fun removeDest(item: Store.Item): Boolean {
    val dest = item.dest ?: return false
    if (dest.startsWith("content:")) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
      val trash = ContentValues()
      trash.put(MediaStore.MediaColumns.IS_TRASHED, 1)
      return try {
        context.contentResolver.update(Uri.parse(dest), trash, null, null) > 0
      } catch (_: Exception) {
        false
      }
    }
    return File(dest).delete()
  }

  @Throws(Exception::class)
  fun runImport(batch: String) {
    val items = store.files("batch=?", batch)
    if (items.isEmpty()) throw IOException("没有可复制的文件")
    begin("import", items)
    var needed = 0L
    for (item in items) if (!verified(item)) needed += maxOf(0L, item.size - validOffset(item))
    if (free(context) < needed + RESERVE)
      throw IOException("空间不足，请清理至少 " + bytes(needed + RESERVE - free(context)))
    var index = 0
    for (item in items) {
      check(false)
      index++
      if (verified(item)) continue
      try {
        copy(item, index, items.size)
      } catch (e: Exception) {
        store.state(item.id, "copy_error", readable(e))
        throw e
      }
    }
    // Persist the all-files verification boundary before any source deletion.
    val ready = ContentValues()
    ready.put("ready", 1)
    store.writableDatabase.update("batches", ready, "id=?", arrayOf(batch))
    message = "拷贝校验完成；卡上原视频保留"
  }

  @Throws(Exception::class)
  fun runDeleteSources(tree: String) {
    val directory = DocumentFile.fromTreeUri(context, Uri.parse(tree))
    if (directory == null || !directory.exists() || !directory.canRead())
      throw IOException("请连接原 TF 卡并确认目录授权")
    val candidates = store.files(
        "source_deleted=0 AND tree=? AND batch IN (SELECT id FROM batches WHERE ready=1)", tree)
    begin("delete-source", candidates)
    val batches = LinkedHashSet<String>()
    for (item in candidates) batches.add(item.batch!!)
    for (batch in batches) {
      for (item in store.files("batch=? AND source_deleted=0", batch)) {
        check(false)
        if (!directory.exists() || !directory.canRead())
          throw IOException("读卡器已断开，未删除的卡上视频保留")
        val source = DocumentFile.fromSingleUri(context, Uri.parse(item.source!!))
        if (source == null || !source.exists()) {
          val v = ContentValues()
          v.put("source_deleted", 1)
          store.update(item.id, v)
          continue
        }
        if (source.length() != item.size
            || (item.modified > 0 && source.lastModified() != item.modified)) {
          val v = ContentValues()
          v.put("error", "原文件已变化，未删除")
          store.update(item.id, v)
          continue
        }
        if (!verified(item) || item.dest == null) continue
        try {
          openDestInput(item.dest!!).use { local ->
            val localHash = Digests.hash(local) { n ->
              check(false)
              progress(item.id, "删除前核对手机副本 · " + item.name, n, item.size)
            }
            if (localHash[0] != item.sha) throw IOException("手机副本校验不符，卡上原文件保留")
          }
        } catch (error: Exception) {
          if (error is Paused) throw error
          val retained = ContentValues()
          retained.put("error", "手机副本无法完整核对，卡上原文件保留")
          store.update(item.id, retained)
          continue
        }
        // Re-read the source at the deletion boundary to avoid deleting replaced content.
        val hash: Array<String>
        context.contentResolver.openInputStream(Uri.parse(item.source!!))!!.use { input ->
          hash = Digests.hash(input) { n ->
            check(false)
            progress(item.id, "删除前核对 · " + item.name, n, item.size)
          }
        }
        if (hash[0] != item.sha) {
          val v = ContentValues()
          v.put("error", "原文件内容已变化，未删除")
          store.update(item.id, v)
          continue
        }
        val v = ContentValues()
        if (source.delete()) {
          v.put("source_deleted", 1)
          v.put("error", "")
        } else v.put("error", "复制已完成，TF 卡删除失败，可重试")
        store.update(item.id, v)
      }
    }
    val retained = store.files(
        "source_deleted=0 AND tree=? AND batch IN (SELECT id FROM batches WHERE ready=1)", tree).size
    message = "卡上删除已处理 " + (candidates.size - retained) + " 个，保留 " + retained + " 个"
  }

  @Throws(Exception::class)
  fun runCleanup() {
    val items = store.files("state='cleanup_error'")
    begin("cleanup", items)
    for (item in items) {
      check(false)
      cleanup(item)
    }
    message = "手机副本清理完成"
  }

  /** Give up the unfinished previous task: remove local copies, drop records; card originals untouched. */
  @Throws(Exception::class)
  fun runAbandon() {
    val items = store.pending()
    begin("abandon", items)
    val batches = items.mapNotNull { it.batch }.toSet()
    var failed = 0
    for (item in items) {
      check(false)
      try {
        val dest = item.dest
        if (dest != null) {
          // Abandon permanently removes legacy MediaStore copies too.
          if (dest.startsWith("content:")) {
            context.contentResolver.delete(Uri.parse(dest), null, null)
            context.contentResolver.query(Uri.parse(dest), arrayOf("_id"), null, null, null)?.use {
              if (it.moveToFirst()) throw IOException("手机副本未删除")
            }
          } else {
            val file = File(dest)
            if (file.exists() && !file.delete()) throw IOException("手机副本未删除")
            file.parentFile?.let { if (it.list()?.isEmpty() == true) it.delete() }
          }
        }
        store.delete(item.id)
      } catch (e: Exception) {
        failed++
        store.state(item.id, "abandon_error", readable(e))
      }
      progress(item.id, "放弃 · " + item.name, item.size, item.size)
    }
    for (batch in batches) if (store.files("batch=?", batch).isEmpty()) store.deleteBatch(batch)
    if (failed > 0) throw IOException("$failed 个手机副本删除失败，请再次点放弃重试")
    taskIds = Collections.emptyList()
    activeId = ""
    done = 0
    total = 0
    phase = TransferProgress.Phase.IDLE
    prefs.edit().putBoolean("upload_paused", true).apply()
    message = "已放弃上次任务，可重新插卡拷贝"
  }

  fun verified(i: Store.Item): Boolean {
    return i.sha != null && i.crc != null
        && listOf("ready", "uploading", "upload_error", "uploaded", "cleanup_error").contains(i.state)
  }

  fun validOffset(i: Store.Item): Long {
    if (i.dest == null) return 0
    return try {
      openDestFd(i.dest!!, false).use { p ->
        minOf(i.offset, minOf(p.statSize, i.size))
      }
    } catch (_: Exception) {
      0
    }
  }

  @Throws(Exception::class)
  fun copy(item: Store.Item, index: Int, count: Int) {
    val resolver = context.contentResolver
    val source = DocumentFile.fromSingleUri(context, Uri.parse(item.source!!))
    if (source == null || !source.exists()) throw IOException("读卡器未连接或原文件不存在")
    if (source.length() != item.size
        || (item.modified > 0 && source.lastModified() != item.modified))
      throw IOException("原文件已变化，请重新扫描")
    if (item.dest != null) {
      try {
        openDestFd(item.dest!!, true).use { }
      } catch (_: Exception) {
        item.dest = null
      }
    }
    if (item.dest == null) {
      // App-private storage: invisible to the gallery, wiped with the app; never a shared MediaStore path.
      val dir = File(context.getExternalFilesDir(null),
          "videos/" + (item.importTime ?: item.date!!) + "_" + item.batch!!.substring(0, 8))
      if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建手机视频目录")
      val destPath = File(dir, item.name).absolutePath
      val saved = ContentValues()
      saved.put("dest", destPath)
      saved.put("offset", 0)
      store.update(item.id, saved)
      item.offset = 0
      item.dest = destPath
    }
    store.state(item.id, "copying", "")
    val offset = validOffset(item)
    resolver.openFileDescriptor(Uri.parse(item.source!!), "r")!!.use { src ->
      openDestFd(item.dest!!, true).use { dst ->
        FileInputStream(src.fileDescriptor).use { input ->
          FileOutputStream(dst.fileDescriptor).use { out ->
            val dest = out.channel
            if (offset > 0) {
              try {
                input.channel.position(offset)
              } catch (_: IOException) {
                var left = offset
                val skipBuffer = ByteArray(128 * 1024)
                while (left > 0) {
                  check(false)
                  val skipped = input.read(skipBuffer, 0, minOf(left, skipBuffer.size.toLong()).toInt())
                  if (skipped < 0) throw IOException("原文件不足以恢复断点")
                  left -= skipped
                }
              }
            }
            dest.truncate(offset)
            dest.position(offset)
            val buffer = ByteArray(1024 * 1024)
            var copied = offset
            var lastSaved = offset
            while (true) {
              val n = input.read(buffer)
              if (n == -1) break
              check(false)
              if (free(context) < RESERVE + n)
                throw IOException("手机空间不足，已暂停并保留 TF 卡原视频")
              out.write(buffer, 0, n)
              copied += n
              progress(item.id, "复制 $index/$count · " + item.name, copied, item.size)
              if (copied - lastSaved >= 8L * 1024 * 1024) {
                out.fd.sync()
                val v = ContentValues()
                v.put("offset", copied)
                store.update(item.id, v)
                lastSaved = copied
              }
            }
            out.fd.sync()
            val v = ContentValues()
            v.put("offset", copied)
            store.update(item.id, v)
            if (copied != item.size) throw IOException("复制长度不一致，原文件保留")
          }
        }
      }
    }
    store.state(item.id, "verifying", "")
    val original: Array<String>
    val local: Array<String>
    resolver.openInputStream(Uri.parse(item.source!!))!!.use { input ->
      original = Digests.hash(input) { n ->
        check(false)
        progress(item.id, "校验 TF 卡 · " + item.name, n, item.size)
      }
    }
    openDestInput(item.dest!!).use { input ->
      local = Digests.hash(input) { n ->
        check(false)
        progress(item.id, "校验手机副本 · " + item.name, n, item.size)
      }
    }
    if (original[0] != local[0] || local[2].toLong() != item.size || original[2].toLong() != item.size) {
      val reset = ContentValues()
      reset.put("offset", 0)
      store.update(item.id, reset)
      throw IOException("内容校验失败，原文件保留，继续将重新复制")
    }
    if (destIsUri(item.dest!!)) {
      // Legacy MediaStore copy: publish it only after verification.
      val published = ContentValues()
      published.put(MediaStore.Video.Media.IS_PENDING, 0)
      resolver.update(Uri.parse(item.dest!!), published, null, null)
    }
    val v = ContentValues()
    v.put("sha", local[0])
    v.put("crc", local[1])
    v.put("state", "ready")
    v.put("error", "")
    store.update(item.id, v)
  }

  @Throws(Exception::class)
  fun runUpload(cellular: Boolean) {
    manualCellular = cellular
    var owner = prefs.getString("owner", null)
    if (owner == null) {
      owner = UUID.randomUUID().toString()
      prefs.edit().putString("owner", owner).apply()
    }
    val items = store.files("state IN ('ready','uploading','upload_error')")
    begin("upload", items)
    for (item in items) {
      check(item.state != "cleanup_error")
      try {
        upload(item, owner)
      } catch (e: Paused) {
        if (item.state != "cleanup_error") store.state(item.id, "ready", "")
        throw e
      } catch (e: Exception) {
        if (item.state != "cleanup_error") store.state(item.id, "upload_error", readable(e))
        throw e
      }
    }
    message = "全部上传完成"
  }

  @Throws(Exception::class)
  fun upload(item: Store.Item, owner: String) {
    if (item.state == "cleanup_error") return
    val local: Array<String>
    openDestInput(item.dest!!).use { input ->
      local = Digests.hash(input) { n ->
        check(true)
        progress(item.id, "上传前核对 · " + item.name, n, item.size)
      }
    }
    if (local[0] != item.sha || local[2].toLong() != item.size)
      throw IOException("手机文件变化，停止上传并保留副本")
    val metadata = JSONObject()
        .put("owner", owner)
        .put("name", item.name)
        .put("size", item.size)
        .put("date", item.date)
        .put("sha256", item.sha)
        .put("crc64", item.crc)
    if (item.importTime != null)
      metadata.put("import_time", item.importTime).put("import_id", item.batch)
    store.state(item.id, "uploading", "")
    val session = Api.call("POST", "/uploads/start", metadata)
    if (!session.optBoolean("uploaded")) {
      val partSize = session.getInt("part_size")
      val parts = session.getJSONArray("parts")
      val complete = HashSet<Int>()
      var completedBytes = 0L
      for (i in 0 until parts.length()) {
        val p = parts.getJSONObject(i)
        val number = p.getInt("number")
        val expected = minOf(partSize.toLong(), item.size - (number - 1L) * partSize)
        if (p.getLong("size") == expected && expected > 0) {
          complete.add(number)
          completedBytes += expected
        }
      }
      val count = ((item.size + partSize - 1) / partSize).toInt()
      progress(item.id, "上传 · " + item.name, completedBytes, item.size)
      openDestFd(item.dest!!, false).use { fd ->
        FileInputStream(fd.fileDescriptor).use { input ->
          for (number in 1..count) {
            check(true)
            if (complete.contains(number)) continue
            val offset = (number - 1L) * partSize
            val length = minOf(partSize.toLong(), item.size - offset)
            input.channel.position(offset)
            val sign = Api.call(
                "POST", "/uploads/" + item.sha + "/part",
                JSONObject().put("owner", owner).put("number", number))
            val connection = URL(sign.getString("url")).openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setFixedLengthStreamingMode(length)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            try {
              val b = ByteArray(128 * 1024)
              var sent = 0L
              connection.outputStream.use { output ->
                while (sent < length) {
                  check(true)
                  val n = input.read(b, 0, minOf(b.size.toLong(), length - sent).toInt())
                  if (n < 0) throw IOException("手机文件不完整")
                  output.write(b, 0, n)
                  sent += n
                  progress(item.id, "上传 · " + item.name, completedBytes + sent, item.size)
                }
              }
              if (connection.responseCode / 100 != 2)
                throw IOException("分片上传失败，继续时会核对云端断点")
            } finally {
              connection.disconnect()
            }
            completedBytes += length
          }
        }
      }
      check(true)
      progress(item.id, "云端完整性校验 · " + item.name, item.size, item.size)
      if (!Api.call("POST", "/uploads/" + item.sha + "/complete", JSONObject().put("owner", owner))
          .optBoolean("verified")) throw IOException("云端核验未通过，手机副本保留")
    }
    // Persist cloud verification before removing. Retry without re-uploading.
    store.state(item.id, "cleanup_error", "")
    item.state = "cleanup_error"
    message = "备份已核验；手机副本保留，可手动删除"
  }

  @Throws(Exception::class)
  fun cleanup(item: Store.Item) {
    val hash: Array<String>
    try {
      openDestInput(item.dest!!).use { input ->
        hash = Digests.hash(input) { n ->
          check(false)
          progress(item.id, "清理手机副本 · " + item.name, n, item.size)
        }
      }
    } catch (_: FileNotFoundException) {
      store.state(item.id, "uploaded", "")
      return
    }
    if (hash[0] != item.sha || hash[2].toLong() != item.size) {
      store.state(item.id, "cleanup_error", "手机文件已变化，保留文件")
      return
    }
    progress(item.id, "清理手机副本 · " + item.name, item.size, item.size)
    if (removeDest(item)) store.state(item.id, "uploaded", "")
    else store.state(item.id, "cleanup_error", "删除失败，手机副本保留，可重试")
  }

  class Paused : IOException()

  companion object {
    val BUSY = AtomicBoolean()
    @JvmField var stopped = false
    @JvmField var pauseReason = "已暂停，可继续"
    @JvmField var message = "准备就绪"
    @JvmField var activeId = ""
    @JvmField var done = 0L
    @JvmField var total = 0L
    @JvmField var started = 0L
    @JvmField var phaseBase = 0L
    @JvmField var taskKind = ""
    @JvmField var outcome = "idle"
    @JvmField var generation = 0L
    @JvmField var taskIds: List<String> = Collections.emptyList()
    @JvmField var phase: TransferProgress.Phase = TransferProgress.Phase.IDLE

    const val RESERVE = 5L * 1024 * 1024 * 1024

    fun destIsUri(dest: String): Boolean = dest.startsWith("content:")

    @JvmStatic
    fun stop(c: Context) {
      pauseReason = "已暂停，可继续"
      stopped = true
      c.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", true).apply()
      message = "已暂停，可继续"
    }

    @JvmStatic
    fun free(c: Context): Long =
        StatFs(c.filesDir.absolutePath).availableBytes

    @JvmStatic
    fun bytes(n: Long): String {
      if (n < 1024) return "$n B"
      if (n < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", n / 1024.0)
      if (n < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", n / (1024.0 * 1024))
      return String.format(Locale.CHINA, "%.2f GB", n / (1024.0 * 1024 * 1024))
    }

    @JvmStatic
    fun detail(): String {
      val elapsed = SystemClock.elapsedRealtime() - started
      val speed = if (elapsed > 1000) (done - phaseBase) * 1000.0 / elapsed else 0.0
      val remain = if (speed > 0) ((total - done) / speed).toLong() else -1
      return bytes(done) + " / " + bytes(total) +
          (if (BUSY.get() && speed > 0)
            " · " + bytes(speed.toLong()) + "/s · 约 " +
                (if (remain >= 60) (remain / 60).toString() + " 分钟" else remain.toString() + " 秒")
          else "")
    }

    @JvmStatic
    fun readable(e: Exception): String {
      if (e is Paused) return pauseReason
      if (e is java.net.SocketTimeoutException) return "连接超时，已保留进度，请稍后继续"
      if (e is java.net.UnknownHostException || e is java.net.ConnectException)
        return "无法连接网络，请检查 Wi-Fi 或稍后重试"
      if (e is SecurityException) return "文件夹访问授权失效，请重新选择视频文件夹"
      if (e is org.json.JSONException) return "云端返回异常，稍后可重试"
      val m = e.message
      return if (m == null || m.contains("https://")) "操作未完成，已保留进度，请重试" else m
    }
  }
}
