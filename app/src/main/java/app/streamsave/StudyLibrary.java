package app.streamsave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The online study library: study-packs/index.json in this repository lists
 * the packs an AI assistant has published. The player downloads the matching
 * pack by itself, so the user never handles a pack file. See STUDY_PACK.md.
 *
 * Plain Java so parsing and matching are unit-tested on the JVM.
 */
final class StudyLibrary {
    static final String BASE_URL =
            "https://raw.githubusercontent.com/blsmohamme972-lab/video-downloder-/main/study-packs/";
    static final String INDEX = "index.json";
    private static final int MAX_ENTRIES = 5000;
    /** How StreamSave names a saved download: "<title>_<first 8 hex of the job id>.<ext>" (Publisher.filename). */
    private static final Pattern SAVED_FILE = Pattern.compile("^(.+)_([0-9a-f]{8})(\\.[A-Za-z0-9]{1,8})?$");

    final List<Entry> entries;

    private StudyLibrary(List<Entry> entries) { this.entries = Collections.unmodifiableList(entries); }

    static final class Entry {
        final String id, title, file, youtubeId;
        final List<String> fileNames;
        final int version;

        private Entry(String id, String title, String file, String youtubeId, List<String> fileNames, int version) {
            this.id = id; this.title = title; this.file = file; this.youtubeId = youtubeId;
            this.fileNames = Collections.unmodifiableList(fileNames); this.version = version;
        }

        String url() { return BASE_URL + file; }

        /** Stored with an installed pack, so a newer published version replaces it. */
        String stamp() { return id + "@" + version; }
    }

    static StudyLibrary parse(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("schemaVersion", 1) != 1) throw new IOException("This app reads study library version 1.");
            JSONArray packs = root.getJSONArray("packs");
            if (packs.length() > MAX_ENTRIES) throw new IOException("The study library is too large.");
            List<Entry> entries = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < packs.length(); i++) {
                JSONObject o = packs.getJSONObject(i);
                String what = "Library entry " + (i + 1);
                String id = o.getString("id");
                if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new JSONException(what + " id must be lowercase letters, digits and dashes");
                if (!ids.add(id)) throw new JSONException(what + " repeats id " + id);
                String file = o.optString("file", id + ".json");
                if (!file.matches("[a-z0-9][a-z0-9-]{0,63}\\.(json|zip)")) throw new JSONException(what + " file must be name.json or name.zip");
                String youtubeId = o.optString("youtubeId", "");
                if (!youtubeId.isEmpty() && !youtubeId.matches("[A-Za-z0-9_-]{11}")) throw new JSONException(what + " youtubeId must be 11 characters");
                List<String> names = new ArrayList<>();
                JSONArray fileNames = o.optJSONArray("fileNames");
                if (fileNames != null) {
                    for (int n = 0; n < fileNames.length() && n < 20; n++) {
                        String name = normalizeName(fileNames.getString(n));
                        if (!name.isEmpty()) names.add(name);
                    }
                }
                int version = o.optInt("version", 1);
                if (version < 1) throw new JSONException(what + " version must be 1 or higher");
                String title = StudyPack.limit(o.optString("title", id).trim(), 120);
                entries.add(new Entry(id, title.isEmpty() ? id : title, file, youtubeId, names, version));
            }
            return new StudyLibrary(entries);
        } catch (JSONException error) {
            throw new IOException("The study library list is not valid: " + error.getMessage(), error);
        }
    }

    /** The pack for a video, by YouTube id first, then by file name; null when none matches. */
    Entry match(String youtubeId, String displayName) {
        if (youtubeId != null && !youtubeId.isEmpty()) {
            for (Entry entry : entries) if (entry.youtubeId.equals(youtubeId)) return entry;
        }
        String raw = displayName == null ? "" : displayName;
        Entry entry = byFileName(normalizeName(raw));
        // A file StreamSave saved carries "_<job id>" after the title; match the title alone too.
        if (entry == null) entry = byFileName(normalizeName(withoutSavedSuffix(raw)));
        return entry;
    }

    private Entry byFileName(String name) {
        if (name.length() < 4) return null;
        for (Entry entry : entries) if (entry.fileNames.contains(name)) return entry;
        return null;
    }

    /** The job-id prefix in a file name StreamSave saved ("Title_3f2a9c1b.mkv" -> "3f2a9c1b"), or "". */
    static String savedJobPrefix(String displayName) {
        Matcher m = SAVED_FILE.matcher(displayName == null ? "" : displayName.trim());
        return m.matches() ? m.group(2) : "";
    }

    /** The file name without StreamSave's "_<job id>" suffix; unchanged for other names. */
    static String withoutSavedSuffix(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        Matcher m = SAVED_FILE.matcher(name);
        return m.matches() ? m.group(1) + (m.group(3) == null ? "" : m.group(3)) : name;
    }

    Entry byId(String id) {
        for (Entry entry : entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    /** YouTube video id from any link StreamSave accepts, or "" when there is none. */
    static String youtubeId(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String canonical = Engine.normalizeUrl(url);
            int at = canonical.indexOf("v=");
            return at < 0 ? "" : canonical.substring(at + 2);
        } catch (IllegalArgumentException notYouTube) {
            return "";
        }
    }

    /** Lowercase, accents and extension removed, punctuation collapsed: "My Video (1).MP4" -> "my video 1". */
    static String normalizeName(String name) {
        String value = name.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot <= 6) value = value.substring(0, dot);
        value = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        value = value.toLowerCase(Locale.ROOT);
        // Letters that have no accent to strip but are commonly typed as plain Latin.
        value = value.replace('ı', 'i').replace('ł', 'l').replace('ø', 'o').replace('đ', 'd')
                .replace("ß", "ss").replace("æ", "ae").replace("œ", "oe");
        return value.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
