package com.makewheels.dashcam;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.ScrollView;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.OutputStream;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CopyFlowTest {
  @Test
  public void copyButtonShowsProgressAndCompletionOffersSecondStep() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    ContentResolver resolver = c.getContentResolver();
    String name = "copy-flow-" + UUID.randomUUID() + ".mp4";
    Uri tree = DocumentsContract.buildTreeDocumentUri(c.getPackageName() + ".fixtures", "root");
    c.grantUriPermission(
        c.getPackageName(),
        tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    Uri source =
        DocumentsContract.createDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(tree, "root"), "video/mp4", name);
    byte[] payload = new byte[65536];
    new Random(1).nextBytes(payload);
    try (OutputStream out = resolver.openOutputStream(source)) {
      out.write(payload);
    }
    TransferEngine.taskIds = Collections.emptyList();
    TransferEngine.taskKind = "";
    TransferEngine.outcome = "idle";
    TransferEngine.BUSY.set(false);
    c.getSharedPreferences("settings", 0)
        .edit()
        .putString("source_tree", tree.toString())
        .putLong("update_checked", System.currentTimeMillis())
        .apply();
    MainActivity activity =
        (MainActivity)
            i.startActivitySync(
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      long inventoryDeadline = SystemClock.elapsedRealtime() + 10000;
      while (activity.inventorySignature.isEmpty() && SystemClock.elapsedRealtime() < inventoryDeadline)
        SystemClock.sleep(100);
      i.runOnMainSync(
          () -> {
            ((ScrollView) activity.pages[0]).scrollTo(0, 2000);
            if (activity.inventoryDialog != null) activity.inventoryDialog.dismiss();
            activity.copyButton.performClick();
          });
      long deadline = SystemClock.elapsedRealtime() + 20000;
      while ((!"completed".equals(TransferEngine.outcome) || TransferEngine.BUSY.get())
          && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
      assertEquals("completed", TransferEngine.outcome);
      assertTrue(
          "copy unexpectedly deleted source", DocumentFile.fromSingleUri(c, source).exists());
      List<Store.Item> files = activity.store.files("name=?", name);
      assertEquals(1, files.size());
      assertEquals("ready", files.get(0).state);
      i.runOnMainSync(activity::refresh);
      i.waitForIdleSync();
      SystemClock.sleep(900);
      i.runOnMainSync(
          () -> {
            assertEquals(0, ((ScrollView) activity.pages[0]).getScrollY());
            assertEquals(View.VISIBLE, activity.homeTask.card.getVisibility());
            assertEquals("100%", activity.homeTask.percent.getText().toString());
            assertTrue(activity.homeTask.action.getText().toString().contains("下一步"));
            assertTrue(activity.liveHint.getText().toString().contains("第一步已完成"));
            assertFalse(activity.ejectButton.isEnabled() && TransferEngine.BUSY.get());
          });
      new UiFlowTest().shot("copy-complete");
      i.runOnMainSync(() -> activity.homeTask.action.performClick());
      assertEquals(1, activity.tab);
      assertEquals("② 上传云端", activity.secondStep.getText().toString());
    } finally {
      TransferEngine.stopped = true;
      for (Store.Item file : activity.store.files("name=?", name)) {
        if (file.dest != null) resolver.delete(Uri.parse(file.dest), null, null);
        activity.store.getWritableDatabase().delete("files", "id=?", new String[] {file.id});
        activity.store.getWritableDatabase().delete("batches", "id=?", new String[] {file.batch});
      }
      if (source != null && DocumentFile.fromSingleUri(c, source).exists())
        DocumentsContract.deleteDocument(resolver, source);
      activity.prefs.edit().remove("source_tree").apply();
      TransferEngine.BUSY.set(false);
      TransferEngine.taskIds = Collections.emptyList();
      TransferEngine.taskKind = "";
      TransferEngine.outcome = "idle";
      i.runOnMainSync(activity::finish);
    }
  }
}
