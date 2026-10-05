package com.makewheels.dashcam;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import java.io.File;

final class SourcePath {
  static String display(Context context, Uri tree) {
    try {
      if ("com.android.externalstorage.documents".equals(tree.getAuthority())) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        if (colon >= 0) {
          String volume = id.substring(0, colon), relative = id.substring(colon + 1);
          File root = null;
          if ("primary".equals(volume)) root = Environment.getExternalStorageDirectory();
          else {
            StorageManager manager =
                (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume mounted : manager.getStorageVolumes()) {
              if (volume.equalsIgnoreCase(mounted.getUuid())) {
                if (Build.VERSION.SDK_INT >= 30) root = mounted.getDirectory();
                break;
              }
            }
            // Standard external-storage provider IDs are Android volume mount names.
            if (root == null && volume.matches("[a-fA-F0-9]{4}-[a-fA-F0-9]{4}"))
              root = new File("/storage", volume);
          }
          if (root != null) return new File(root, relative).getAbsolutePath();
        }
      }
    } catch (Exception ignored) {
    }
    return "此文件提供方未公开磁盘绝对路径，完整授权位置：\n" + tree;
  }
}
