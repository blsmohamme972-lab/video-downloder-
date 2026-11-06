package app.streamsave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Videos opened in the Study player, newest first. The Library shows them, so a
 * video opened from Drive or Files stays one tap away instead of disappearing
 * (the Library's download list only holds StreamSave's own downloads).
 *
 * Stored as JSON in the Study player's preferences; plain Java so it is
 * unit-tested on the JVM. Unknown or broken data reads as an empty list.
 */
final class StudyRecents {
    static final int MAX = 30;

    static final class Item {
        final String uri, source, title, youtubeId;
        final long openedAt;

        Item(String uri, String source, String title, String youtubeId, long openedAt) {
            this.uri = uri == null ? "" : uri;
            this.source = source == null || source.isEmpty() ? this.uri : source;
            this.title = title == null ? "" : title;
            this.youtubeId = youtubeId == null ? "" : youtubeId;
            this.openedAt = openedAt;
        }
    }

    private StudyRecents() { }

    static List<Item> parse(String json) {
        List<Item> items = new ArrayList<>();
        if (json == null || json.isEmpty()) return items;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && items.size() < MAX; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null || o.optString("uri", "").isEmpty()) continue;
                items.add(new Item(o.optString("uri", ""), o.optString("source", ""), o.optString("title", ""),
                        o.optString("youtubeId", ""), o.optLong("openedAt", 0)));
            }
        } catch (JSONException broken) {
            return new ArrayList<>();
        }
        return Collections.unmodifiableList(items);
    }

    /** The list with {@code item} first; an older entry for the same video is replaced. */
    static String add(String json, Item item) {
        List<Item> items = new ArrayList<>();
        items.add(item);
        for (Item old : parse(json)) {
            if (old.source.equals(item.source) || old.uri.equals(item.uri)) continue;
            if (items.size() < MAX) items.add(old);
        }
        return write(items);
    }

    static String remove(String json, String uri) {
        List<Item> items = new ArrayList<>();
        for (Item old : parse(json)) if (!old.uri.equals(uri)) items.add(old);
        return write(items);
    }

    /** "Title_1a2b3c4d.mkv" -> "Title": the file name without StreamSave's download id and the extension. */
    static String displayTitle(String fileName) {
        String name = StudyLibrary.withoutSavedSuffix(fileName == null ? "" : fileName.trim());
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) name = name.substring(0, dot);
        return name.isEmpty() ? "Video" : name;
    }

    private static String write(List<Item> items) {
        JSONArray array = new JSONArray();
        try {
            for (Item item : items) {
                array.put(new JSONObject().put("uri", item.uri).put("source", item.source).put("title", item.title)
                        .put("youtubeId", item.youtubeId).put("openedAt", item.openedAt));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return array.toString();
    }
}
