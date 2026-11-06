package app.streamsave;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, explicitly granted access to completed app-scoped files on Android 8–9. */
public final class DownloadProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    static File root(Context context) {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return external == null ? new File(context.getFilesDir(), "saved") : external;
    }

    static Uri uriFor(Context context, File file) throws IOException {
        File root = root(context);
        if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
            throw new IOException("Invalid saved file location.");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".files")
                .appendPath(file.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".files").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1) throw new FileNotFoundException("Invalid media URI");
        String name = uri.getLastPathSegment();
        if (name == null || name.endsWith(".saving")) throw new FileNotFoundException("File unavailable");
        try {
            File root = root(getContext()).getCanonicalFile();
            File file = new File(root, name).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.isFile()) throw new FileNotFoundException("File unavailable");
            return file;
        } catch (IOException e) { throw new FileNotFoundException("File unavailable"); }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only media access");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try { return App.mimeFor(resolve(uri), false); }
        catch (FileNotFoundException e) { return "application/octet-stream"; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns, 1);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
            return result;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException("Read-only"); }
}
