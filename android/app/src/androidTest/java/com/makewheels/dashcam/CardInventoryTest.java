package com.makewheels.dashcam;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.OutputStream;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CardInventoryTest {
  @Test public void detectsFilesUpdatesAfterDeletionAndClearsOnDisconnect() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    Uri tree = DocumentsContract.buildTreeDocumentUri(c.getPackageName() + ".fixtures", "root");
    c.grantUriPermission(c.getPackageName(), tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    Uri file = DocumentsContract.createDocument(c.getContentResolver(),
        DocumentsContract.buildDocumentUriUsingTree(tree, "root"), "video/mp4",
        "inventory-" + UUID.randomUUID() + ".mp4");
    try (OutputStream out = c.getContentResolver().openOutputStream(file)) {
      out.write(new byte[77777]);
    }
    c.getSharedPreferences("settings", 0).edit().putString("source_tree", tree.toString())
        .putLong("update_checked", System.currentTimeMillis()).apply();
    MainActivity activity = (MainActivity) i.startActivitySync(new Intent(c, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      waitFor(activity, "1 个视频");
      shot("card-detected");
      i.runOnMainSync(() -> {
        assertTrue(activity.inventoryDialog.isShowing());
        assertTrue(activity.inventorySummary.contains("76.0 KB"));
        activity.inventoryDialog.dismiss();
        activity.inspectCard(true);
      });
      SystemClock.sleep(500);
      i.runOnMainSync(() -> assertFalse(activity.inventoryDialog.isShowing()));
      DocumentsContract.deleteDocument(c.getContentResolver(), file);
      i.runOnMainSync(() -> activity.inspectCard(true));
      waitFor(activity, "0 个视频");
      shot("card-empty");
      i.runOnMainSync(() -> {
        assertTrue(activity.inventoryDialog.isShowing());
        activity.cardPresent = false;
        activity.clearInventory();
        activity.refresh();
        assertEquals("", activity.inventorySummary);
        assertFalse(activity.inventoryDialog.isShowing());
        assertFalse(activity.ejectButton.isEnabled());
      });
    } finally {
      try { DocumentsContract.deleteDocument(c.getContentResolver(), file); }
      catch (Exception ignored) { }
      activity.prefs.edit().remove("source_tree").apply();
      i.runOnMainSync(() -> { activity.clearInventory(); activity.finish(); });
    }
  }
  void shot(String name) throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    i.waitForIdleSync();
    SystemClock.sleep(300);
    android.graphics.Bitmap image = i.getUiAutomation().takeScreenshot();
    assertNotNull(image);
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
        new java.io.File(i.getTargetContext().getCacheDir(), name + ".png"))) {
      image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
    }
    image.recycle();
  }
  void waitFor(MainActivity activity, String expected) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (!activity.inventorySummary.contains(expected) && SystemClock.elapsedRealtime() < deadline)
      SystemClock.sleep(100);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(
        () -> assertTrue(activity.inventorySummary, activity.inventorySummary.contains(expected)));
  }
}
