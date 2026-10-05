package com.makewheels.dashcam;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Isolated debug-only document storage for explicit deletion boundary tests. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
  static final String[] COLUMNS = {
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_FLAGS,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED
  };

  File file(String id) throws FileNotFoundException {
    File root = new File(getContext().getCacheDir(), "transfer-documents");
    root.mkdirs();
    if (id.equals("root")) return root;
    if (!id.matches("[a-zA-Z0-9._-]+") || id.equals("..") || id.equals("."))
      throw new FileNotFoundException();
    return new File(root, id);
  }

  void add(MatrixCursor cursor, String id) throws FileNotFoundException {
    File f = file(id);
    if (!f.exists()) return;
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String column : cursor.getColumnNames()) {
      switch (column) {
        case Document.COLUMN_DOCUMENT_ID:
          row.add(id);
          break;
        case Document.COLUMN_DISPLAY_NAME:
          row.add(f.getName());
          break;
        case Document.COLUMN_MIME_TYPE:
          row.add(f.isDirectory() ? Document.MIME_TYPE_DIR : "video/mp4");
          break;
        case Document.COLUMN_FLAGS:
          row.add(
              f.isDirectory()
                  ? Document.FLAG_DIR_SUPPORTS_CREATE
                  : Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE);
          break;
        case Document.COLUMN_SIZE:
          row.add(f.length());
          break;
        case Document.COLUMN_LAST_MODIFIED:
          row.add(f.lastModified());
          break;
        default:
          row.add(null);
      }
    }
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    try {
      return parent.equals("root") && !child.equals("root") && file(child).exists();
    } catch (FileNotFoundException error) {
      return false;
    }
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    return new MatrixCursor(new String[] {"root_id"});
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
    add(cursor, id);
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments(String parent, String[] projection, String sort)
      throws FileNotFoundException {
    MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
    File[] children = file(parent).listFiles();
    if (children != null) for (File child : children) add(cursor, child.getName());
    return cursor;
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public String createDocument(String parent, String mime, String name)
      throws FileNotFoundException {
    if (!parent.equals("root")) throw new FileNotFoundException();
    try {
      if (!file(name).createNewFile()) throw new FileNotFoundException();
      return name;
    } catch (java.io.IOException error) {
      throw new FileNotFoundException();
    }
  }

  @Override
  public void deleteDocument(String id) throws FileNotFoundException {
    if (id.equals("root") || !file(id).delete()) throw new FileNotFoundException();
  }
}
