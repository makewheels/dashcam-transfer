package com.makewheels.dashcam;

import static org.junit.Assert.*;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RecycleTest {
  @Test
  public void verifiedUploadMovesToTrashAndBytesAreRecoverable() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ContentResolver resolver = context.getContentResolver();
    Store store = new Store(context);
    byte[] bytes = "recycle test video bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String id = "recycle-test-" + java.util.UUID.randomUUID();
    Uri uri = null;
    try {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Video.Media.DISPLAY_NAME, id + ".mp4");
      values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
      values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/行车视频测试/");
      values.put(MediaStore.Video.Media.IS_PENDING, 1);
      uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(uri);
      try (OutputStream out = resolver.openOutputStream(uri)) {
        out.write(bytes);
      }
      values.clear();
      values.put(MediaStore.Video.Media.IS_PENDING, 0);
      resolver.update(uri, values, null, null);
      String[] digest = Digests.hash(new ByteArrayInputStream(bytes), n -> {});
      values.clear();
      values.put("id", id);
      values.put("dest", uri.toString());
      values.put("size", bytes.length);
      values.put("name", id + ".mp4");
      values.put("sha", digest[0]);
      values.put("crc", digest[1]);
      values.put("state", "cleanup_error");
      store.getWritableDatabase().insertOrThrow("files", null, values);
      TransferEngine.stopped = false;
      TransferEngine engine = new TransferEngine(context);
      engine.runUpload(false);
      assertEquals("cleanup_error", store.files("id=?", id).get(0).state);
      try (Cursor before =
          resolver.query(
              uri, new String[] {MediaStore.MediaColumns.IS_TRASHED}, null, null, null)) {
        assertNotNull(before);
        assertTrue(before.moveToFirst());
        assertEquals(0, before.getInt(0));
      }
      engine.runCleanup();
      if (Build.VERSION.SDK_INT >= 30) {
        assertEquals("uploaded", store.files("id=?", id).get(0).state);
        Bundle query = new Bundle();
        query.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor =
            resolver.query(uri, new String[] {MediaStore.MediaColumns.IS_TRASHED}, query, null)) {
          assertNotNull(cursor);
          assertTrue(cursor.moveToFirst());
          assertEquals(1, cursor.getInt(0));
        }
      } else assertEquals("cleanup_error", store.files("id=?", id).get(0).state);
      try (InputStream in = resolver.openInputStream(uri)) {
        assertArrayEquals(bytes, in.readAllBytes());
      }
    } finally {
      // Only this test's freshly created object is removed; no user media is queried.
      if (uri != null) resolver.delete(uri, null, null);
      store.getWritableDatabase().delete("files", "id=?", new String[] {id});
      store.close();
    }
  }
}
