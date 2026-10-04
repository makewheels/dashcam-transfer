package com.makewheels.dashcam;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.os.storage.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StorageAndMigrationTest {
  @Test
  public void upgradeKeepsExistingQueueAndResumeOffset() {
    SQLiteDatabase db = SQLiteDatabase.create(null);
    Store helper = new Store(InstrumentationRegistry.getInstrumentation().getTargetContext());
    try {
      db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY,state TEXT,offset INTEGER)");
      db.execSQL("INSERT INTO files VALUES('existing','copying',8388608)");
      helper.onUpgrade(db, 1, 2);
      try (Cursor c =
          db.rawQuery("SELECT state,offset,import_time FROM files WHERE id='existing'", null)) {
        assertTrue(c.moveToFirst());
        assertEquals("copying", c.getString(0));
        assertEquals(8388608, c.getLong(1));
        assertTrue(c.isNull(2));
      }
    } finally {
      db.close();
      helper.close();
    }
  }

  String shell(Instrumentation i, String command) throws IOException {
    try (ParcelFileDescriptor fd = i.getUiAutomation().executeShellCommand(command);
        InputStream in = new FileInputStream(fd.getFileDescriptor());
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toString("UTF-8");
    }
  }

  @Test
  public void removableStorageMountAndRemovalUpdateGuidance() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context c = i.getTargetContext();
    StorageManager manager = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
    StorageVolume virtual = null;
    for (StorageVolume v : manager.getStorageVolumes())
      if (v.isRemovable() && v.getDescription(c).contains("Virtual SD")) virtual = v;
    Assume.assumeNotNull(virtual);
    String uuid = virtual.getUuid(), volumeId = null;
    for (String line : shell(i, "sm list-volumes public").split("\n")) {
      String[] fields = line.trim().split(" +");
      if (fields.length >= 3 && fields[2].equals(uuid)) volumeId = fields[0];
    }
    assertNotNull(volumeId);
    assertTrue(volumeId.startsWith("public:"));
    c.getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("intro_seen", true)
        .putBoolean("upload_paused", true)
        .putLong("update_checked", System.currentTimeMillis())
        .apply();
    MainActivity activity =
        (MainActivity)
            i.startActivitySync(
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      assertTrue(activity.cardPresent);
      shell(i, "sm unmount " + volumeId);
      long deadline = SystemClock.elapsedRealtime() + 8000;
      while (activity.cardPresent && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(100);
      assertFalse("Removal notification missing", activity.cardPresent);
      shell(i, "sm mount " + volumeId);
      deadline = SystemClock.elapsedRealtime() + 8000;
      while (!activity.cardPresent && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(100);
      assertTrue("Mount notification missing", activity.cardPresent);
      i.runOnMainSync(
          () -> assertTrue(activity.otgState.getText().toString().contains("TF 卡已检测到")));
    } finally {
      shell(i, "sm mount " + volumeId);
      i.runOnMainSync(activity::finish);
    }
  }
}
