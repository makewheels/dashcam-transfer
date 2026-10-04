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
    SystemClock.sleep(600);
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
      shot("onboarding-connect");
      clickDialog("下一步");
      shot("onboarding-import");
      clickDialog("下一步");
      shot("onboarding-upload");
      clickDialog("开始使用");
      SystemClock.sleep(400);
      // Dismiss a notification prompt if the test device presents it.
      AccessibilityNodeInfo root = instrument.getUiAutomation().getRootInActiveWindow();
      if (root != null)
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Allow"))
          node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      instrument.runOnMainSync(() -> assertTrue(contains(activity.root, "选择视频文件夹")));
      shot("home-connect");
      instrument.runOnMainSync(() -> activity.selectTab(1));
      instrument.waitForIdleSync();
      instrument.runOnMainSync(() -> assertTrue(contains(activity.root, "去导入视频")));
      shot("queue-empty");
      instrument.runOnMainSync(() -> activity.selectTab(2));
      instrument.waitForIdleSync();
      SystemClock.sleep(300);
      instrument.runOnMainSync(
          () -> {
            try {
              activity.cloudItems =
                  new org.json.JSONArray()
                      .put(
                          new org.json.JSONObject()
                              .put("name", "20261004_090001_FRONT.mp4")
                              .put("date", "2026-10-04")
                              .put("import_time", "2026-10-04_21-35-08")
                              .put("size", 512L * 1024 * 1024)
                              .put("state", "uploaded"));
              activity.renderCloud();
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      shot("cloud-videos");
      instrument.runOnMainSync(() -> activity.selectTab(3));
      instrument.waitForIdleSync();
      instrument.runOnMainSync(() -> assertTrue(contains(activity.root, "手机副本进入回收站")));
      shot("settings");
      ContentValues file = new ContentValues();
      file.put("id", id);
      file.put("batch", "ui-review");
      file.put("name", "20261004_090001_FRONT.mp4");
      file.put("size", 512L * 1024 * 1024);
      file.put("date", "2026-10-04");
      file.put("import_time", "2026-10-04_21-35-08");
      file.put("state", "copying");
      activity.store.getWritableDatabase().insertOrThrow("files", null, file);
      context.getSharedPreferences("settings", 0).edit().putBoolean("upload_paused", false).apply();
      TransferEngine.BUSY.set(true);
      TransferEngine.stopped = false;
      TransferEngine.taskKind = "import";
      TransferEngine.taskIds = java.util.Collections.singletonList(id);
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
            activity.refresh();
          });
      shot("import-progress");
      file.clear();
      file.put("state", "uploading");
      file.put("sha", "test");
      activity.store.update(id, file);
      TransferEngine.taskKind = "upload";
      TransferEngine.phase = TransferProgress.Phase.UPLOAD;
      TransferEngine.message = "上传 · 20261004_090001_FRONT.mp4";
      instrument.runOnMainSync(
          () -> {
            activity.selectTab(1);
            activity.refresh();
          });
      shot("upload-progress");
      activity.store.getWritableDatabase().delete("files", "id=?", new String[] {id});
    } finally {
      activity.store.getWritableDatabase().delete("files", "id=?", new String[] {id});
      TransferEngine.BUSY.set(false);
      TransferEngine.taskIds = java.util.Collections.emptyList();
      TransferEngine.taskKind = "";
      TransferEngine.activeId = "";
      instrument.runOnMainSync(activity::finish);
    }
  }
}
