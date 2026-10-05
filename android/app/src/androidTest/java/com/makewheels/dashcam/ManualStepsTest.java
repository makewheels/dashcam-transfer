package com.makewheels.dashcam;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ManualStepsTest {
  @Test
  public void copyingKeepsSourceEvenForLegacyAutoDeleteBatchAndDeleteRechecksPhone()
      throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ContentResolver r = c.getContentResolver();
    Store store = new Store(c);
    String id = "manual-" + UUID.randomUUID(), authority = c.getPackageName() + ".fixtures";
    Uri tree = DocumentsContract.buildTreeDocumentUri(authority, "root");
    c.grantUriPermission(
        c.getPackageName(),
        tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, "root");
    Uri src = null, dst = null;
    byte[] payload =
        "complete original video bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try {
      src = DocumentsContract.createDocument(r, root, "video/mp4", id + ".mp4");
      assertNotNull(src);
      try (OutputStream out = r.openOutputStream(src)) {
        out.write(payload);
      }
      ContentValues v = new ContentValues();
      v.put(MediaStore.Video.Media.DISPLAY_NAME, id + ".mp4");
      v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
      v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/行车视频测试/");
      dst = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
      assertNotNull(dst);
      try (OutputStream out = r.openOutputStream(dst)) {
        out.write(payload);
      }
      String[] hash = Digests.hash(new ByteArrayInputStream(payload), n -> {});
      v.clear();
      v.put("id", id);
      v.put("tree", tree.toString());
      v.put("ready", 0);
      v.put("delete_source", 1);
      store.getWritableDatabase().insertOrThrow("batches", null, v);
      v.clear();
      v.put("id", id);
      v.put("batch", id);
      v.put("tree", tree.toString());
      v.put("source", src.toString());
      v.put("dest", dst.toString());
      v.put("name", id + ".mp4");
      v.put("size", payload.length);
      v.put("sha", hash[0]);
      v.put("crc", hash[1]);
      v.put("state", "waiting");
      store.getWritableDatabase().insertOrThrow("files", null, v);
      TransferEngine.stopped = false;
      TransferEngine engine = new TransferEngine(c);
      engine.runImport(id);
      assertTrue(DocumentFile.fromSingleUri(c, src).exists());
      assertEquals("ready", store.files("id=?", id).get(0).state);
      try (OutputStream out = r.openOutputStream(dst, "wt")) {
        out.write("changed phone copy".getBytes());
      }
      engine.runDeleteSources(tree.toString());
      assertTrue(DocumentFile.fromSingleUri(c, src).exists());
      assertEquals(0, store.files("id=?", id).get(0).sourceDeleted ? 1 : 0);
      try (OutputStream out = r.openOutputStream(dst, "wt")) {
        out.write(payload);
      }
      engine.runDeleteSources(tree.toString());
      assertFalse(DocumentFile.fromSingleUri(c, src).exists());
    } finally {
      if (src != null && DocumentFile.fromSingleUri(c, src).exists())
        DocumentsContract.deleteDocument(r, src);
      if (dst != null) r.delete(dst, null, null);
      store.getWritableDatabase().delete("files", "id=?", new String[] {id});
      store.getWritableDatabase().delete("batches", "id=?", new String[] {id});
      store.close();
    }
  }

  @Test
  public void directoryDisplayKeepsVolumeAndEveryPathSegment() {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String path =
        SourcePath.display(
            c,
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", "ABCD-1234:DCIM/recorder/video"));
    assertEquals("/storage/ABCD-1234/DCIM/recorder/video", path);
    String unknown =
        SourcePath.display(c, Uri.parse("content://other.provider/tree/whole%2Flocation"));
    assertTrue(unknown.contains("content://other.provider/tree/whole%2Flocation"));
    assertTrue(unknown.contains("未公开"));
  }
}
