package app.streamsave;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Grants the system installer read-only access to exactly one staged update APK.
 * The file remains app-private and the provider is not exported.
 */
public final class AppUpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    static File stagedApk(android.content.Context context) {
        return new File(new File(context.getCacheDir(), "app-updates"), "update.apk");
    }

    static Uri contentUri(android.content.Context context) {
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".updates")
                .appendPath("update.apk").build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || !"/update.apk".equals(uri.getPath())) {
            throw new FileNotFoundException("Invalid update URI");
        }
        File staged = stagedApk(getContext());
        if (!staged.isFile()) throw new FileNotFoundException("Update not downloaded");
        return staged;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try {
            resolve(uri);
            return "application/vnd.android.package-archive";
        } catch (FileNotFoundException ex) {
            return "application/octet-stream";
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] args, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException ex) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}
