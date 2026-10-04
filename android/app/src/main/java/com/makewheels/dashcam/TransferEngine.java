package com.makewheels.dashcam;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class TransferEngine {
  static final AtomicBoolean BUSY = new AtomicBoolean();
  static volatile boolean stopped = false;
  static volatile String pauseReason = "已暂停，可继续";
  static volatile String message = "准备就绪", activeId = "";
  static volatile long done = 0, total = 0, started = 0, phaseBase = 0;
  static volatile String taskKind = "";
  static volatile List<String> taskIds = Collections.emptyList();
  static volatile TransferProgress.Phase phase = TransferProgress.Phase.IDLE;

  void begin(String kind, List<Store.Item> items) {
    taskKind = kind;
    ArrayList<String> ids = new ArrayList<>();
    for (Store.Item item : items) ids.add(item.id);
    taskIds = Collections.unmodifiableList(ids);
    activeId = "";
    done = total = 0;
    phase = TransferProgress.Phase.IDLE;
    message = kind.equals("import") ? "准备导入" : "准备上传";
  }

  static final long RESERVE = 5L * 1024 * 1024 * 1024;
  final Context context;
  final Store store;
  final android.content.SharedPreferences prefs;
  boolean manualCellular = false;

  TransferEngine(Context c) {
    context = c.getApplicationContext();
    store = new Store(context);
    prefs = context.getSharedPreferences("settings", 0);
  }

  static void stop(Context c) {
    pauseReason = "已暂停，可继续";
    stopped = true;
    c.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", true).apply();
    message = "已暂停，可继续";
  }

  boolean wifi() {
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkCapabilities n = cm.getNetworkCapabilities(cm.getActiveNetwork());
    return n != null
        && n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  boolean connected() {
    ConnectivityManager cm =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkCapabilities n = cm.getNetworkCapabilities(cm.getActiveNetwork());
    return n != null && n.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  void check(boolean uploading) throws Exception {
    if (stopped || Thread.currentThread().isInterrupted()) throw new Paused();
    if (uploading && (!connected() || (!wifi() && !manualCellular))) throw new Paused();
  }

  static class Paused extends IOException {}

  void progress(String id, String phase, long current, long max) {
    if (!id.equals(activeId) || !phase.equals(message)) {
      started = SystemClock.elapsedRealtime();
      phaseBase = current;
    }
    activeId = id;
    message = phase;
    TransferEngine.phase = TransferProgress.detect(phase);
    done = current;
    total = max;
  }

  static long free(Context c) {
    return new StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes();
  }

  static String bytes(long n) {
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", n / 1024.0);
    if (n < 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", n / (1024.0 * 1024));
    return String.format(Locale.CHINA, "%.2f GB", n / (1024.0 * 1024 * 1024));
  }

  static String detail() {
    long elapsed = SystemClock.elapsedRealtime() - started;
    double speed = elapsed > 1000 ? (done - phaseBase) * 1000.0 / elapsed : 0;
    long remain = speed > 0 ? (long) ((total - done) / speed) : -1;
    return bytes(done)
        + " / "
        + bytes(total)
        + (BUSY.get() && speed > 0
            ? " · "
                + bytes((long) speed)
                + "/s · 约 "
                + (remain >= 60 ? remain / 60 + " 分钟" : remain + " 秒")
            : "");
  }

  void runImport(String batch) throws Exception {
    List<Store.Item> items = store.files("batch=?", batch);
    if (items.isEmpty()) throw new IOException("没有可复制的文件");
    begin("import", items);
    long needed = 0;
    for (Store.Item item : items)
      if (!verified(item)) needed += Math.max(0, item.size - validOffset(item));
    if (free(context) < needed + RESERVE)
      throw new IOException("空间不足，请清理至少 " + bytes(needed + RESERVE - free(context)));
    int index = 0;
    for (Store.Item item : items) {
      check(false);
      index++;
      if (verified(item)) continue;
      try {
        copy(item, index, items.size());
      } catch (Exception e) {
        store.state(item.id, "copy_error", readable(e));
        throw e;
      }
    }
    // Persist the all-files verification boundary before any source deletion.
    ContentValues ready = new ContentValues();
    ready.put("ready", 1);
    store.getWritableDatabase().update("batches", ready, "id=?", new String[] {batch});
    boolean remove = false;
    try (Cursor c =
        store
            .getReadableDatabase()
            .rawQuery("SELECT delete_source FROM batches WHERE id=?", new String[] {batch})) {
      if (c.moveToFirst()) remove = c.getInt(0) != 0;
    }
    if (remove) {
      for (Store.Item item : store.files("batch=? AND source_deleted=0", batch)) {
        check(false);
        DocumentFile source = DocumentFile.fromSingleUri(context, Uri.parse(item.source));
        if (source == null || !source.exists()) {
          ContentValues v = new ContentValues();
          v.put("source_deleted", 1);
          store.update(item.id, v);
          continue;
        }
        if (source.length() != item.size
            || (item.modified > 0 && source.lastModified() != item.modified)) {
          ContentValues v = new ContentValues();
          v.put("error", "原文件已变化，未删除");
          store.update(item.id, v);
          continue;
        }
        // Re-read the source at the deletion boundary to avoid deleting replaced content.
        String[] hash;
        try (InputStream in =
            context.getContentResolver().openInputStream(Uri.parse(item.source))) {
          hash =
              Digests.hash(
                  in,
                  n -> {
                    check(false);
                    progress(item.id, "删除前核对 · " + item.name, n, item.size);
                  });
        }
        if (!hash[0].equals(item.sha)) {
          ContentValues v = new ContentValues();
          v.put("error", "原文件内容已变化，未删除");
          store.update(item.id, v);
          continue;
        }
        ContentValues v = new ContentValues();
        if (source.delete()) {
          v.put("source_deleted", 1);
          v.put("error", "");
        } else v.put("error", "复制已完成，TF 卡删除失败，可重试");
        store.update(item.id, v);
      }
    }
    message = "复制与校验完成";
  }

  boolean verified(Store.Item i) {
    return i.sha != null
        && i.crc != null
        && Arrays.asList("ready", "uploading", "upload_error", "uploaded", "cleanup_error")
            .contains(i.state);
  }

  long validOffset(Store.Item i) {
    if (i.dest == null) return 0;
    try (ParcelFileDescriptor p =
        context.getContentResolver().openFileDescriptor(Uri.parse(i.dest), "r")) {
      return Math.min(i.offset, Math.min(p.getStatSize(), i.size));
    } catch (Exception e) {
      return 0;
    }
  }

  void copy(Store.Item item, int index, int count) throws Exception {
    ContentResolver resolver = context.getContentResolver();
    DocumentFile source = DocumentFile.fromSingleUri(context, Uri.parse(item.source));
    if (source == null || !source.exists()) throw new IOException("读卡器未连接或原文件不存在");
    if (source.length() != item.size
        || (item.modified > 0 && source.lastModified() != item.modified))
      throw new IOException("原文件已变化，请重新扫描");
    Uri target = item.dest == null ? null : Uri.parse(item.dest);
    if (target != null) {
      try (ParcelFileDescriptor ignored = resolver.openFileDescriptor(target, "rw")) {
      } catch (Exception e) {
        target = null;
      }
    }
    if (target == null) {
      ContentValues v = new ContentValues();
      v.put(MediaStore.Video.Media.DISPLAY_NAME, item.name);
      String type = URLConnection.guessContentTypeFromName(item.name);
      v.put(
          MediaStore.Video.Media.MIME_TYPE,
          type != null && type.startsWith("video/") ? type : "video/mp4");
      // A unique directory prevents overwriting a same-name video from another import.
      v.put(
          MediaStore.Video.Media.RELATIVE_PATH,
          "Movies/行车视频/"
              + (item.importTime != null ? item.importTime : item.date)
              + "_"
              + item.batch.substring(0, 8));
      v.put(MediaStore.Video.Media.IS_PENDING, 1);
      target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
      if (target == null) throw new IOException("无法创建手机视频文件");
      ContentValues saved = new ContentValues();
      saved.put("dest", target.toString());
      saved.put("offset", 0);
      store.update(item.id, saved);
      item.offset = 0;
      item.dest = target.toString();
    }
    store.state(item.id, "copying", "");
    long offset = validOffset(item);
    try (ParcelFileDescriptor src = resolver.openFileDescriptor(Uri.parse(item.source), "r");
        ParcelFileDescriptor dst = resolver.openFileDescriptor(target, "rw");
        FileInputStream in = new FileInputStream(src.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(dst.getFileDescriptor())) {
      FileChannel dest = out.getChannel();
      if (offset > 0) {
        try {
          in.getChannel().position(offset);
        } catch (IOException nonseek) {
          long left = offset;
          byte[] skipBuffer = new byte[128 * 1024];
          while (left > 0) {
            check(false);
            int skipped = in.read(skipBuffer, 0, (int) Math.min(left, skipBuffer.length));
            if (skipped < 0) throw new IOException("原文件不足以恢复断点");
            left -= skipped;
          }
        }
      }
      dest.truncate(offset);
      dest.position(offset);
      byte[] buffer = new byte[1024 * 1024];
      long copied = offset, lastSaved = offset;
      int n;
      while ((n = in.read(buffer)) != -1) {
        check(false);
        if (free(context) < RESERVE + n) throw new IOException("手机空间不足，已暂停并保留 TF 卡原视频");
        out.write(buffer, 0, n);
        copied += n;
        progress(item.id, "复制 " + index + "/" + count + " · " + item.name, copied, item.size);
        if (copied - lastSaved >= 8L * 1024 * 1024) {
          out.getFD().sync();
          ContentValues v = new ContentValues();
          v.put("offset", copied);
          store.update(item.id, v);
          lastSaved = copied;
        }
      }
      out.getFD().sync();
      ContentValues v = new ContentValues();
      v.put("offset", copied);
      store.update(item.id, v);
      if (copied != item.size) throw new IOException("复制长度不一致，原文件保留");
    }
    store.state(item.id, "verifying", "");
    String[] original, local;
    try (InputStream in = resolver.openInputStream(Uri.parse(item.source))) {
      original =
          Digests.hash(
              in,
              n -> {
                check(false);
                progress(item.id, "校验 TF 卡 · " + item.name, n, item.size);
              });
    }
    try (InputStream in = resolver.openInputStream(target)) {
      local =
          Digests.hash(
              in,
              n -> {
                check(false);
                progress(item.id, "校验手机副本 · " + item.name, n, item.size);
              });
    }
    if (!original[0].equals(local[0])
        || Long.parseLong(local[2]) != item.size
        || Long.parseLong(original[2]) != item.size) {
      ContentValues reset = new ContentValues();
      reset.put("offset", 0);
      store.update(item.id, reset);
      throw new IOException("内容校验失败，原文件保留，继续将重新复制");
    }
    ContentValues published = new ContentValues();
    published.put(MediaStore.Video.Media.IS_PENDING, 0);
    resolver.update(target, published, null, null);
    ContentValues v = new ContentValues();
    v.put("sha", local[0]);
    v.put("crc", local[1]);
    v.put("state", "ready");
    v.put("error", "");
    store.update(item.id, v);
  }

  void runUpload(boolean cellular) throws Exception {
    manualCellular = cellular;
    String owner = prefs.getString("owner", null);
    if (owner == null) {
      owner = UUID.randomUUID().toString();
      prefs.edit().putString("owner", owner).apply();
    }
    List<Store.Item> items =
        store.files("state IN ('ready','uploading','upload_error','cleanup_error')");
    begin("upload", items);
    for (Store.Item item : items) {
      check(!item.state.equals("cleanup_error"));
      try {
        upload(item, owner);
      } catch (Paused e) {
        if (!item.state.equals("cleanup_error")) store.state(item.id, "ready", "");
        throw e;
      } catch (Exception e) {
        if (!item.state.equals("cleanup_error")) store.state(item.id, "upload_error", readable(e));
        throw e;
      }
    }
    message = "全部上传完成";
  }

  void upload(Store.Item item, String owner) throws Exception {
    if (item.state.equals("cleanup_error")) {
      cleanup(item);
      return;
    }
    Uri uri = Uri.parse(item.dest);
    String[] local;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      local =
          Digests.hash(
              in,
              n -> {
                check(true);
                progress(item.id, "上传前核对 · " + item.name, n, item.size);
              });
    }
    if (!local[0].equals(item.sha) || Long.parseLong(local[2]) != item.size)
      throw new IOException("手机文件变化，停止上传并保留副本");
    JSONObject metadata =
        new JSONObject()
            .put("owner", owner)
            .put("name", item.name)
            .put("size", item.size)
            .put("date", item.date)
            .put("sha256", item.sha)
            .put("crc64", item.crc);
    if (item.importTime != null)
      metadata.put("import_time", item.importTime).put("import_id", item.batch);
    store.state(item.id, "uploading", "");
    JSONObject session = Api.call("POST", "/uploads/start", metadata);
    if (!session.optBoolean("uploaded")) {
      int partSize = session.getInt("part_size");
      JSONArray parts = session.getJSONArray("parts");
      Set<Integer> complete = new HashSet<>();
      long completedBytes = 0;
      for (int i = 0; i < parts.length(); i++) {
        JSONObject p = parts.getJSONObject(i);
        int number = p.getInt("number");
        long expected = Math.min(partSize, item.size - (number - 1L) * partSize);
        if (p.getLong("size") == expected && expected > 0) {
          complete.add(number);
          completedBytes += expected;
        }
      }
      int count = (int) ((item.size + partSize - 1) / partSize);
      progress(item.id, "上传 · " + item.name, completedBytes, item.size);
      try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
          FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
        for (int number = 1; number <= count; number++) {
          check(true);
          if (complete.contains(number)) continue;
          long offset = (number - 1L) * partSize, length = Math.min(partSize, item.size - offset);
          in.getChannel().position(offset);
          JSONObject sign =
              Api.call(
                  "POST",
                  "/uploads/" + item.sha + "/part",
                  new JSONObject().put("owner", owner).put("number", number));
          HttpURLConnection connection =
              (HttpURLConnection) new URL(sign.getString("url")).openConnection();
          connection.setRequestMethod("PUT");
          connection.setDoOutput(true);
          connection.setConnectTimeout(20000);
          connection.setReadTimeout(60000);
          connection.setFixedLengthStreamingMode(length);
          connection.setRequestProperty("Content-Type", "application/octet-stream");
          try {
            byte[] b = new byte[128 * 1024];
            long sent = 0;
            try (OutputStream output = connection.getOutputStream()) {
              while (sent < length) {
                check(true);
                int n = in.read(b, 0, (int) Math.min(b.length, length - sent));
                if (n < 0) throw new IOException("手机文件不完整");
                output.write(b, 0, n);
                sent += n;
                progress(item.id, "上传 · " + item.name, completedBytes + sent, item.size);
              }
            }
            if (connection.getResponseCode() / 100 != 2) throw new IOException("分片上传失败，继续时会核对云端断点");
          } finally {
            connection.disconnect();
          }
          completedBytes += length;
        }
      }
      check(true);
      progress(item.id, "云端完整性校验 · " + item.name, item.size, item.size);
      if (!Api.call(
              "POST", "/uploads/" + item.sha + "/complete", new JSONObject().put("owner", owner))
          .optBoolean("verified")) throw new IOException("云端核验未通过，手机副本保留");
    }
    // Persist cloud verification before trashing. Retry without re-uploading.
    store.state(item.id, "cleanup_error", "");
    item.state = "cleanup_error";
    cleanup(item);
  }

  void cleanup(Store.Item item) throws Exception {
    Uri uri = Uri.parse(item.dest);
    String[] hash;
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      hash =
          Digests.hash(
              in,
              n -> {
                check(false);
                progress(item.id, "回收前核对 · " + item.name, n, item.size);
              });
    } catch (FileNotFoundException e) {
      store.state(item.id, "uploaded", "");
      return;
    }
    if (!hash[0].equals(item.sha) || Long.parseLong(hash[2]) != item.size) {
      store.state(item.id, "cleanup_error", "手机文件已变化，保留文件");
      return;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      store.state(item.id, "cleanup_error", "已上传；此系统不支持系统回收站，手机副本已保留");
      return;
    }
    ContentValues trash = new ContentValues();
    trash.put(MediaStore.MediaColumns.IS_TRASHED, 1);
    progress(item.id, "移入回收站 · " + item.name, item.size, item.size);
    try {
      if (context.getContentResolver().update(uri, trash, null, null) > 0)
        store.state(item.id, "uploaded", "");
      else store.state(item.id, "cleanup_error", "上传成功，移入回收站失败，手机副本已保留");
    } catch (Exception e) {
      store.state(item.id, "cleanup_error", "上传成功，移入回收站失败，手机副本已保留");
    }
  }

  static String readable(Exception e) {
    if (e instanceof Paused) return pauseReason;
    if (e instanceof java.net.SocketTimeoutException) return "连接超时，已保留进度，请稍后继续";
    if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException)
      return "无法连接网络，请检查 Wi-Fi 或稍后重试";
    if (e instanceof SecurityException) return "文件夹访问授权失效，请重新选择视频文件夹";
    if (e instanceof org.json.JSONException) return "云端返回异常，稍后可重试";
    String message = e.getMessage();
    return message == null || message.contains("https://") ? "操作未完成，已保留进度，请重试" : message;
  }
}
