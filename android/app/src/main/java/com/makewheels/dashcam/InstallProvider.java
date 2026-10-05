package com.makewheels.dashcam;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class InstallProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  private File file(Uri u) throws FileNotFoundException {
    if (!"/update.apk".equals(u.getPath())) throw new FileNotFoundException();
    return new File(getContext().getCacheDir(), "update.apk");
  }

  @Override
  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri u) {
    return "application/vnd.android.package-archive";
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
    try {
      File f = file(u);
      MatrixCursor c =
          new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
      c.addRow(new Object[] {"行车记录仪转存.apk", f.length()});
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
