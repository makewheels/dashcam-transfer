package com.makewheels.dashcam

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

internal object SourcePath {
  @JvmStatic
  fun display(context: Context, tree: Uri): String {
    try {
      if ("com.android.externalstorage.documents" == tree.authority) {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val colon = id.indexOf(':')
        if (colon >= 0) {
          val volume = id.substring(0, colon)
          val relative = id.substring(colon + 1)
          var root: File? = null
          if ("primary" == volume) root = Environment.getExternalStorageDirectory()
          else {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (mounted in manager.storageVolumes) {
              if (volume.equals(mounted.uuid, ignoreCase = true)) {
                if (Build.VERSION.SDK_INT >= 30) root = mounted.directory
                break
              }
            }
            // Standard external-storage provider IDs are Android volume mount names.
            if (root == null && volume.matches(Regex("[a-fA-F0-9]{4}-[a-fA-F0-9]{4}")))
              root = File("/storage", volume)
          }
          if (root != null) return File(root, relative).absolutePath
        }
      }
    } catch (_: Exception) {
    }
    return "此文件提供方未公开磁盘绝对路径，完整授权位置：\n$tree"
  }
}
