package app.streamsave;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Serializes every state transition atomically; progress writes are throttled by the service. */
final class JobStore {
    private final AtomicFile file;
    private final ArrayList<Job> rows = new ArrayList<>();
    String warning = "";

    JobStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "downloads-v1.json"));
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return;
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            JSONArray array = root.getJSONArray("jobs");
            for (int i = 0; i < array.length(); i++) rows.add(Job.fromJson(array.getJSONObject(i)));
        } catch (Exception e) {
            warning = "Download history could not be read. Existing video files are safe.";
            File backup = new File(context.getFilesDir(), "downloads-unreadable-" + System.currentTimeMillis() + ".json");
            if (file.getBaseFile().exists()) file.getBaseFile().renameTo(backup);
            rows.clear();
        }
    }

    synchronized List<Job> list() {
        ArrayList<Job> result = new ArrayList<>();
        for (Job row : rows) result.add(row.copy());
        Collections.reverse(result);
        return result;
    }

    synchronized Job get(String id) {
        for (Job row : rows) if (row.id.equals(id)) return row.copy();
        return null;
    }

    synchronized Job next() {
        for (Job row : rows) if (row.state == State.QUEUED) return row.copy();
        return null;
    }

    synchronized void add(Job job) throws IOException {
        rows.add(job.copy());
        try { save(); }
        catch (IOException e) { rows.remove(rows.size() - 1); throw e; }
    }

    synchronized void change(String id, Consumer<Job> update) throws IOException {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).id.equals(id)) continue;
            Job before = rows.get(i).copy();
            update.accept(rows.get(i));
            try { save(); }
            catch (IOException e) { rows.set(i, before); throw e; }
            return;
        }
    }

    synchronized boolean transition(String id, Predicate<Job> allowed, Consumer<Job> update) throws IOException {
        for (int i = 0; i < rows.size(); i++) {
            Job row = rows.get(i);
            if (!row.id.equals(id) || !allowed.test(row)) continue;
            Job before = row.copy();
            update.accept(row);
            try { save(); }
            catch (IOException e) { rows.set(i, before); throw e; }
            return true;
        }
        return false;
    }

    synchronized void remove(String id) throws IOException {
        removeIf(id, j -> true);
    }

    synchronized boolean removeIf(String id, Predicate<Job> allowed) throws IOException {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).id.equals(id)) continue;
            if (!allowed.test(rows.get(i))) return false;
            Job before = rows.remove(i);
            try { save(); }
            catch (IOException e) { rows.add(i, before); throw e; }
            return true;
        }
        return false;
    }

    private void save() throws IOException {
        FileOutputStream stream = null;
        try {
            JSONObject root = new JSONObject();
            JSONArray array = new JSONArray();
            for (Job row : rows) array.put(row.toJson());
            root.put("schema", 1); root.put("jobs", array);
            stream = file.startWrite();
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("Could not save download state. Free some device storage and try again.", e);
        }
    }
}
