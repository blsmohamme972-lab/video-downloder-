package app.streamsave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-video study data in app storage: the imported study pack, the user's own
 * timestamped notes and quiz answers. Each video has its own folder named by a
 * hash of its source (a download id or a content URI). Writes are atomic.
 *
 * Plain Java so it is unit-tested on the JVM.
 */
final class StudyStore {
    static final int MAX_NOTE_LENGTH = 1000;
    private static final String PACK = "pack.bin";
    private static final String NOTES = "notes.json";
    private static final String ANSWERS = "answers.json";

    private final File root;

    StudyStore(File root) { this.root = root; }

    /** Stable folder key for a video source such as "job:<id>" or a content:// URI. */
    static String keyFor(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private File folder(String key) throws IOException {
        if (!key.matches("[0-9a-f]{32}")) throw new IOException("Invalid study key.");
        File folder = new File(root, key);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create study storage.");
        return folder;
    }

    // ----- study pack -----

    void savePack(String key, byte[] raw) throws IOException { write(new File(folder(key), PACK), raw); }

    byte[] loadPack(String key) throws IOException {
        File file = new File(folder(key), PACK);
        return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
    }

    void deletePack(String key) throws IOException {
        File file = new File(folder(key), PACK);
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove the study pack.");
        File answers = new File(folder(key), ANSWERS);
        if (answers.exists() && !answers.delete()) throw new IOException("Cannot clear quiz answers.");
    }

    // ----- notes -----

    static final class Note {
        final String id;
        final long timeMs;
        final String text;
        final long createdAt;

        Note(String id, long timeMs, String text, long createdAt) {
            this.id = id; this.timeMs = timeMs; this.text = text; this.createdAt = createdAt;
        }
    }

    synchronized List<Note> notes(String key) throws IOException {
        File file = new File(folder(key), NOTES);
        List<Note> notes = new ArrayList<>();
        if (!file.isFile()) return notes;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                notes.add(new Note(o.getString("id"), Math.max(0, o.getLong("timeMs")),
                        o.getString("text"), o.optLong("createdAt", 0)));
            }
        } catch (JSONException error) {
            throw new IOException("Saved notes could not be read.", error);
        }
        notes.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return notes;
    }

    synchronized Note addNote(String key, long timeMs, String text, long now) throws IOException {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IOException("Write something before saving the note.");
        if (clean.length() > MAX_NOTE_LENGTH) clean = clean.substring(0, MAX_NOTE_LENGTH);
        List<Note> notes = notes(key);
        Note note = new Note(UUID.randomUUID().toString(), Math.max(0, timeMs), clean, now);
        notes.add(note);
        saveNotes(key, notes);
        return note;
    }

    synchronized void deleteNote(String key, String noteId) throws IOException {
        List<Note> notes = notes(key);
        Iterator<Note> iterator = notes.iterator();
        while (iterator.hasNext()) if (iterator.next().id.equals(noteId)) iterator.remove();
        saveNotes(key, notes);
    }

    private void saveNotes(String key, List<Note> notes) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (Note note : notes) {
                array.put(new JSONObject().put("id", note.id).put("timeMs", note.timeMs)
                        .put("text", note.text).put("createdAt", note.createdAt));
            }
        } catch (JSONException error) {
            throw new IOException("Cannot save notes.", error);
        }
        write(new File(folder(key), NOTES), array.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ----- quiz answers -----

    synchronized Map<String, Integer> answers(String key) throws IOException {
        File file = new File(folder(key), ANSWERS);
        Map<String, Integer> answers = new LinkedHashMap<>();
        if (!file.isFile()) return answers;
        try {
            JSONObject object = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String id = names.next();
                answers.put(id, object.getInt(id));
            }
        } catch (JSONException error) {
            throw new IOException("Saved quiz answers could not be read.", error);
        }
        return answers;
    }

    synchronized void saveAnswers(String key, Map<String, Integer> answers) throws IOException {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Integer> entry : answers.entrySet()) object.put(entry.getKey(), entry.getValue());
        } catch (JSONException error) {
            throw new IOException("Cannot save quiz answers.", error);
        }
        write(new File(folder(key), ANSWERS), object.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ----- helpers -----

    private static void write(File target, byte[] data) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            if (!temporary.delete()) temporary.deleteOnExit();
            throw new IOException("Cannot save study data.");
        }
    }
}
