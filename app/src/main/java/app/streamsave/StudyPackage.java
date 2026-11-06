package app.streamsave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A data-only, versioned surgical annotation ZIP. No code, HTML, URLs, or scripts
 * in the archive are executed. Coordinates are normalized to the visible video.
 */
final class StudyPackage {
    private static final int MAX_ENTRIES = 48;
    private static final int MAX_CUES = 2500;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    final String title;
    final long durationMs;
    final List<Cue> cues;
    final Map<String, byte[]> images;

    private StudyPackage(String title, long durationMs, List<Cue> cues, Map<String, byte[]> images) {
        this.title = title;
        this.durationMs = durationMs;
        this.cues = Collections.unmodifiableList(cues);
        this.images = Collections.unmodifiableMap(images);
    }

    static StudyPackage read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Cannot open the annotation ZIP.");
        byte[] manifest = null;
        Map<String, byte[]> images = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int entries = 0;
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Annotation ZIP has too many files.");
                String name = entry.getName();
                if (name == null || name.startsWith("/") || name.contains("..")
                        || name.contains("\\") || name.indexOf('\0') >= 0
                        || !seen.add(name)) throw new IOException("Unsafe ZIP entry.");
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                // Ignore unrecognized assets, while still counting their inflated size.
                boolean isManifest = name.equals("manifest.json");
                boolean isImage = name.matches("images/[a-zA-Z0-9_-]{1,64}\\.png");
                int limit = isManifest ? MAX_MANIFEST_BYTES : isImage ? MAX_IMAGE_BYTES : 1024 * 1024;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_TOTAL_BYTES || out.size() + n > limit)
                        throw new IOException("Annotation ZIP exceeds the safe size limit.");
                    if (isManifest || isImage) out.write(buffer, 0, n);
                }
                if (isManifest) manifest = out.toByteArray();
                if (isImage) images.put(name, out.toByteArray());
                zip.closeEntry();
            }
        }
        if (manifest == null) throw new IOException("ZIP must contain manifest.json.");
        try {
            JSONObject root = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
            if (root.getInt("schemaVersion") != 1)
                throw new IOException("Unsupported annotation version. Expected schemaVersion 1.");
            JSONArray items = root.getJSONArray("cues");
            if (items.length() > MAX_CUES) throw new IOException("Too many annotation cues.");
            List<Cue> cues = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                Cue cue = Cue.parse(items.getJSONObject(i));
                if (cue.type.equals("image") && !images.containsKey(cue.asset))
                    throw new IOException("Missing image asset: " + cue.asset);
                cues.add(cue);
            }
            cues.sort((a, b) -> Long.compare(a.startMs, b.startMs));
            String title = root.optString("title", "Surgical study").trim();
            return new StudyPackage(title.length() > 100 ? title.substring(0, 100) : title,
                    Math.max(0L, root.optLong("durationMs", 0)), cues, images);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid annotation manifest: " + error.getMessage(), error);
        }
    }

    static final class Position {
        final float x, y, x2, y2;
        Position(float x, float y, float x2, float y2) {
            this.x = x; this.y = y; this.x2 = x2; this.y2 = y2;
        }
        static Position from(JSONObject json, Position fallback) throws JSONException {
            return new Position(coord(json, "x", fallback.x), coord(json, "y", fallback.y),
                    coord(json, "x2", fallback.x2), coord(json, "y2", fallback.y2));
        }
        private static float coord(JSONObject json, String key, float fallback) throws JSONException {
            double n = json.has(key) ? json.getDouble(key) : fallback;
            if (!Double.isFinite(n) || n < 0 || n > 1) throw new JSONException(key + " must be between 0 and 1");
            return (float) n;
        }
        static Position mix(Position a, Position b, float t) {
            return new Position(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                    a.x2 + (b.x2 - a.x2) * t, a.y2 + (b.y2 - a.y2) * t);
        }
    }

    static final class Keyframe {
        final long timeMs;
        final Position position;
        Keyframe(long timeMs, Position position) { this.timeMs = timeMs; this.position = position; }
    }

    static final class Cue {
        final String id, type, text, color, asset;
        final long startMs, endMs;
        final Position position;
        final List<Keyframe> keyframes;

        Cue(String id, String type, String text, String color, String asset,
            long startMs, long endMs, Position position, List<Keyframe> keyframes) {
            this.id = id; this.type = type; this.text = text; this.color = color; this.asset = asset;
            this.startMs = startMs; this.endMs = endMs;
            this.position = position;
            this.keyframes = Collections.unmodifiableList(keyframes);
        }

        static Cue parse(JSONObject object) throws JSONException {
            String type = object.getString("type");
            if (!(type.equals("note") || type.equals("label") || type.equals("arrow")
                    || type.equals("ring") || type.equals("box") || type.equals("image"))) {
                throw new JSONException("Unknown cue type: " + type);
            }
            long start = object.getLong("startMs");
            long end = object.getLong("endMs");
            if (start < 0 || end <= start || end > 172800000L)
                throw new JSONException("Invalid cue time range.");
            String text = object.optString("text", "").trim();
            if (text.length() > 600) throw new JSONException("Annotation text exceeds 600 characters.");
            String color = object.optString("color", "#64E8BF");
            if (!color.matches("#[a-fA-F0-9]{6}")) throw new JSONException("Invalid color.");
            String asset = object.optString("asset", "");
            if (type.equals("image") && !asset.matches("images/[a-zA-Z0-9_-]{1,64}\\.png"))
                throw new JSONException("Image cues must reference an images/*.png asset.");
            Position base = Position.from(object, new Position(0.5f, 0.5f, 0.62f, 0.6f));
            JSONArray frames = object.optJSONArray("keyframes");
            List<Keyframe> keys = new ArrayList<>();
            if (frames != null) {
                if (frames.length() > 300) throw new JSONException("Too many tracking keyframes.");
                long last = -1;
                for (int i = 0; i < frames.length(); i++) {
                    JSONObject frame = frames.getJSONObject(i);
                    long time = frame.getLong("timeMs");
                    if (time < start || time > end || time <= last)
                        throw new JSONException("Tracking keyframes must be ordered and inside the cue.");
                    last = time;
                    keys.add(new Keyframe(time, Position.from(frame, base)));
                }
            }
            return new Cue(object.optString("id", type + "-" + start), type, text, color,
                    asset, start, end, base, keys);
        }

        boolean active(long timeMs) { return startMs <= timeMs && timeMs < endMs; }

        Position at(long timeMs) {
            if (keyframes.isEmpty()) return position;
            Keyframe first = keyframes.get(0);
            if (timeMs <= first.timeMs) return first.position;
            for (int i = 1; i < keyframes.size(); i++) {
                Keyframe next = keyframes.get(i);
                if (timeMs <= next.timeMs) {
                    Keyframe prior = keyframes.get(i - 1);
                    float fraction = (float) (timeMs - prior.timeMs) / (next.timeMs - prior.timeMs);
                    return Position.mix(prior.position, next.position, fraction);
                }
            }
            return keyframes.get(keyframes.size() - 1).position;
        }
    }
}
