package app.streamsave;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Publishes only complete media, with pending MediaStore rows for crash-safe export. */
final class Publisher {
    private Publisher() { }

    static void save(App app, Job job, File source, BooleanSupplier stopping) throws IOException {
        File work = app.workDir(job.id);
        if (!source.getCanonicalPath().startsWith(work.getCanonicalPath() + File.separator)
                || !source.isFile() || source.length() == 0) {
            throw new IOException("The completed video file is missing. Resume the download to try again.");
        }
        String name = filename(job, source);
        String mime = App.mimeFor(source, job.audioOnly);
        if (Build.VERSION.SDK_INT >= 29) {
            saveMediaStore(app, job, source, name, mime, stopping);
        } else {
            saveLegacy(app, job, source, name, stopping);
        }
        app.cleanupWork(job.id);
    }

    @android.annotation.TargetApi(29)
    private static void saveMediaStore(App app, Job job, File source, String name,
                                       String mime, BooleanSupplier stopping) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamSave");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Android could not create a file in Downloads.");
        boolean publicFile = false;
        try {
            app.requireChange(job.id, j -> j.pendingUri = uri.toString());
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Android could not open the download destination.");
                copy(source, out, stopping);
            }
            if (stopping.getAsBoolean()) throw new CancellationException("Saving interrupted");
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1)
                throw new IOException("Android could not finish publishing the saved video.");
            publicFile = true;
            markComplete(app, job.id, uri, source.length());
        } catch (RuntimeException | IOException e) {
            // Once public, retain the pending URI so startup recovery can finish the history commit.
            if (!publicFile) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
                try { app.requireChange(job.id, j -> j.pendingUri = ""); } catch (IOException ignored) { }
            }
            throw e;
        }
    }

    private static void saveLegacy(App app, Job job, File source, String name,
                                   BooleanSupplier stopping) throws IOException {
        File root = DownloadProvider.root(app);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create the saved-files folder.");
        File dest = new File(root, name);
        File temp = new File(root, name + ".saving");
        if (!dest.exists()) {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                copy(source, out, stopping);
                out.getFD().sync();
            } catch (RuntimeException | IOException e) { temp.delete(); throw e; }
            if (!temp.renameTo(dest)) { temp.delete(); throw new IOException("Cannot finish saving the video file."); }
        }
        markComplete(app, job.id, DownloadProvider.uriFor(app, dest), dest.length());
    }

    private static void copy(File source, OutputStream out, BooleanSupplier stopping) throws IOException {
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (stopping.getAsBoolean()) throw new CancellationException("Saving interrupted");
                out.write(buffer, 0, count);
            }
            out.flush();
        }
    }

    static void markComplete(App app, String id, Uri uri, long bytes) throws IOException {
        app.requireChange(id, j -> {
            j.contentUri = uri.toString(); j.pendingUri = ""; j.localFile = "";
            j.state = State.COMPLETED; j.progress = 100; j.bytes = bytes;
            j.completedAt = System.currentTimeMillis(); j.message = "";
            j.statusLine = Build.VERSION.SDK_INT >= 29 ? "Saved to Downloads/StreamSave" : "Saved · use Share to copy anywhere";
        });
    }

    static void recover(App app) {
        if (Build.VERSION.SDK_INT < 29) return;
        ContentResolver resolver = app.getContentResolver();
        for (Job job : app.jobs()) {
            if (job.pendingUri.isEmpty()) continue;
            try {
                Uri uri = Uri.parse(job.pendingUri);
                if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())) continue;
                boolean complete = false;
                long size = 0;
                try (Cursor c = resolver.query(uri,
                        new String[]{MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        complete = c.getInt(0) == 0;
                        size = c.getLong(1);
                    }
                }
                if (complete && size > 0) {
                    markComplete(app, job.id, uri, size);
                    app.cleanupWork(job.id);
                } else {
                    resolver.delete(uri, null, null);
                    app.requireChange(job.id, j -> j.pendingUri = "");
                }
            } catch (Exception ignored) {
                // Keep identity for the next attempt; never delete an unverified external URI.
            }
        }
    }

    static String filename(Job job, File source) {
        String ext = source.getName().contains(".")
                ? source.getName().substring(source.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "mkv";
        if (!ext.matches("[a-z0-9]{1,8}")) ext = job.audioOnly ? "m4a" : "mkv";
        String title = job.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        title = title.replaceAll("^[. ]+|[. ]+$", "");
        if (title.isEmpty()) title = "YouTube video";
        while (title.getBytes(StandardCharsets.UTF_8).length > 170) {
            int end = title.offsetByCodePoints(title.length(), -1);
            title = title.substring(0, end);
        }
        return title + "_" + job.id.substring(0, 8) + "." + ext;
    }
}
