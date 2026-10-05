package com.makewheels.dashcam;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UiFlowTest {
  final Instrumentation instrument = InstrumentationRegistry.getInstrumentation();

  void shot(String name) throws Exception {
    instrument.waitForIdleSync();
    SystemClock.sleep(3500);
    AccessibilityNodeInfo window = instrument.getUiAutomation().getRootInActiveWindow();
    if (window != null) {
      for (AccessibilityNodeInfo node : window.findAccessibilityNodeInfosByText("稍后"))
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    instrument.waitForIdleSync();
    SystemClock.sleep(300);
    Bitmap bitmap = instrument.getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    try (FileOutputStream out =
        new FileOutputStream(
            new File(instrument.getTargetContext().getCacheDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
  }

  void clickDialog(String text) {
    AccessibilityNodeInfo root = instrument.getUiAutomation().getRootInActiveWindow();
    assertNotNull(root);
    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
      if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
    fail("Action missing: " + text);
  }

  boolean contains(View view, String value) {
    if (view.getVisibility() != View.VISIBLE) return false;
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(value))
      return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (contains(group.getChildAt(i), value)) return true;
    }
    return false;
  }

  @Test
  public void guidanceNavigationAndProgressHaveClearNextActions() throws Exception {
    TransferEngine.message = "准备就绪";
    TransferEngine.taskIds = java.util.Collections.emptyList();
    TransferEngine.taskKind = "";
    TransferEngine.outcome = "idle";
    TransferEngine.phase = TransferProgress.Phase.IDLE;
    String id = "ui-test-" + java.util.UUID.randomUUID();
    Context context = instrument.getTargetContext();
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("intro_seen", false)
        .putLong("update_checked", System.currentTimeMillis())
        .putBoolean("upload_paused", true)
        .apply();
    MainActivity activity =
        (MainActivity)
            instrument.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      instrument.runOnMainSync(
          () -> {
            assertTrue(contains(activity.root, "拷贝到手机"));
            assertFalse(contains(activity.root, "待上传"));
            assertTrue(contains(activity.root, "删除卡上已拷贝的视频"));
            assertTrue(contains(activity.root, "下一步"));
            assertFalse(contains(activity.root, "设置"));
            assertTrue(contains(activity.root, "TF 卡"));
          });
      SystemClock.sleep(3500);
      shot("home-status");
      instrument.runOnMainSync(
          () -> {
            activity
                .prefs
                .edit()
                .putString(
                    "source_tree",
                    android.provider.DocumentsContract.buildTreeDocumentUri(
                            "com.android.externalstorage.documents",
                            "ABCD-1234:DCIM/recorder/video")
                        .toString())
                .apply();
            activity.refresh();
            assertEquals(
                "/storage/ABCD-1234/DCIM/recorder/video", activity.source.getText().toString());
          });
      shot("folder-path");
      boolean connected = activity.cardPresent;
      instrument.runOnMainSync(
          () -> {
            activity.cardPresent = false;
            activity.readerPresent = false;
            activity.refresh();
          });
      shot("home-disconnected");
      instrument.runOnMainSync(
          () -> {
            activity.cardPresent = connected;
            activity.refresh();
          });

      instrument.runOnMainSync(() -> activity.selectTab(1));
      instrument.waitForIdleSync();
      instrument.runOnMainSync(() -> assertTrue(contains(activity.root, "去导入视频")));
      shot("queue-empty");
      ContentValues file = new ContentValues();
      file.put("id", id);
      file.put("batch", "ui-review");
      file.put("name", "20261004_090001_FRONT.mp4");
      file.put("size", 512L * 1024 * 1024);
      file.put("date", "2026-10-04");
      file.put("import_time", "2026-10-04_21-35-08");
      file.put("state", "copying");
      activity.store.getWritableDatabase().insertOrThrow("files", null, file);
      file.put("id", id + "-second");
      file.put("name", "20261004_090002_FRONT.mp4");
      activity.store.getWritableDatabase().insertOrThrow("files", null, file);
      context.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", false).apply();
      TransferEngine.BUSY.set(true);
      TransferEngine.stopped = false;
      TransferEngine.taskKind = "import";
      TransferEngine.taskIds = java.util.Arrays.asList(id, id + "-second");
      TransferEngine.generation = 101;
      TransferEngine.activeId = id;
      TransferEngine.phase = TransferProgress.Phase.COPY;
      TransferEngine.done = 192L * 1024 * 1024;
      TransferEngine.total = 512L * 1024 * 1024;
      TransferEngine.phaseBase = 0;
      TransferEngine.started = SystemClock.elapsedRealtime() - 15000;
      TransferEngine.message = "复制 1/1 · 20261004_090001_FRONT.mp4";
      instrument.runOnMainSync(
          () -> {
            activity.selectTab(0);
            ((android.widget.ScrollView) activity.pages[0]).scrollTo(0, 2000);
            activity.progressWasVisible = false;
            activity.batchEstimate.generation = Long.MIN_VALUE;
            activity.batchEstimate.remaining(
                101, SystemClock.elapsedRealtime() - 1500, 0, 3.0 * 1024 * 1024 * 1024, true);
            activity.refresh();
          });
      instrument.waitForIdleSync();
      SystemClock.sleep(900);
      instrument.runOnMainSync(
          () -> {
            assertEquals(0, ((android.widget.ScrollView) activity.pages[0]).getScrollY());
            assertEquals("6%", activity.homeTask.percent.getText().toString());
            assertFalse(activity.ejectButton.isEnabled());
            assertTrue(activity.homeTask.totalEta.getText().toString().startsWith("约 "));
            assertFalse(contains(activity.root, "当前阶段剩余"));
            assertFalse(contains(activity.root, "当前文件剩余"));
            android.graphics.Rect bounds = new android.graphics.Rect();
            assertTrue(activity.homeTask.percent.getGlobalVisibleRect(bounds));
          });
      shot("import-progress");
      file.clear();
      file.put("state", "uploading");
      file.put("sha", "test");
      activity.store.update(id, file);
      TransferEngine.taskKind = "upload";
      TransferEngine.generation = 102;
      TransferEngine.phase = TransferProgress.Phase.UPLOAD;
      TransferEngine.message = "上传 · 20261004_090001_FRONT.mp4";
      instrument.runOnMainSync(
          () -> {
            activity.batchEstimate.remaining(
                102, SystemClock.elapsedRealtime() - 1500, 0, 1024.0 * 1024 * 1024, true);
            activity.selectTab(1);
            activity.refresh();
          });
      shot("upload-progress");
      TransferEngine.BUSY.set(false);
      context.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", true).apply();
      instrument.runOnMainSync(() -> activity.selectTab(2));
      instrument.waitForIdleSync();
      SystemClock.sleep(300);
      instrument.runOnMainSync(
          () -> {
            activity.batchesLoading = false;
            activity.batchStatus.setText("本机导入记录 · 云端历史可联网同步");
            activity.renderBatches();
          });
      shot("batch-history");
      instrument.runOnMainSync(
          () -> {
            MainActivity.BatchView batch = activity.new BatchView();
            batch.id = "ui-review";
            batch.time = "2026-10-04_21-35-08";
            batch.local = activity.store.files("id=?", id);
            activity.showBatch(batch);
          });
      shot("batch-detail");
      instrument.runOnMainSync(() -> activity.homePause.setVisibility(View.GONE));
      activity.prefs.edit().remove("source_tree").apply();
      activity
          .store
          .getWritableDatabase()
          .delete("files", "id IN (?,?)", new String[] {id, id + "-second"});
    } finally {
      activity.prefs.edit().remove("source_tree").apply();
      activity
          .store
          .getWritableDatabase()
          .delete("files", "id IN (?,?)", new String[] {id, id + "-second"});
      TransferEngine.BUSY.set(false);
      TransferEngine.taskIds = java.util.Collections.emptyList();
      TransferEngine.taskKind = "";
      TransferEngine.outcome = "idle";
      TransferEngine.activeId = "";
      instrument.runOnMainSync(activity::finish);
    }
  }
}
