package app.streamsave;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A study pack for one video: animated drawings, timed multiple-choice
 * questions and captions, produced by an AI that watched and listened to the
 * video. Data only: nothing inside a pack is ever executed. A pack is either a
 * JSON file or a ZIP with manifest.json plus images/*.png. See STUDY_PACK.md.
 *
 * Plain Java (no Android types) so the parser is unit-tested on the JVM.
 */
final class StudyPack {
    static final long MAX_TIME_MS = 48L * 3600 * 1000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 64;
    private static final int MAX_OVERLAYS = 3000;
    private static final int MAX_QUESTIONS = 1000;
    private static final int MAX_CAPTIONS = 20000;
    private static final int MAX_NARRATION = 3000;
    private static final String IMAGE_NAME = "images/[a-zA-Z0-9_-]{1,64}\\.png";

    final String title;
    final String language;
    final long durationMs;
    final List<Overlay> overlays;
    final List<Question> questions;
    final List<Caption> captions;
    /** Spoken commentary, synthesized on the phone (optional; schema 2). */
    final List<Narration> narration;
    final Map<String, byte[]> images;

    private StudyPack(String title, String language, long durationMs, List<Overlay> overlays,
                      List<Question> questions, List<Caption> captions, List<Narration> narration,
                      Map<String, byte[]> images) {
        this.title = title;
        this.language = language;
        this.durationMs = durationMs;
        this.overlays = Collections.unmodifiableList(overlays);
        this.questions = Collections.unmodifiableList(questions);
        this.captions = Collections.unmodifiableList(captions);
        this.narration = Collections.unmodifiableList(narration);
        this.images = Collections.unmodifiableMap(images);
    }

    /** Reads a JSON pack or a ZIP pack (detected from its first bytes). */
    static StudyPack read(byte[] data) throws IOException {
        if (data == null || data.length == 0) throw new IOException("The study pack is empty.");
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
            return readZip(data);
        }
        if (data.length > MAX_JSON_BYTES) throw new IOException("The study pack is larger than 4 MB.");
        return parse(text(data), new HashMap<>());
    }

    private static StudyPack readZip(byte[] data) throws IOException {
        byte[] manifest = null;
        Map<String, byte[]> images = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("The study pack ZIP has too many files.");
                String name = entry.getName();
                if (name == null || name.startsWith("/") || name.contains("..") || name.contains("\\")
                        || name.indexOf('\0') >= 0 || !seen.add(name)) {
                    throw new IOException("The study pack ZIP contains an unsafe file name.");
                }
                if (entry.isDirectory()) continue;
                boolean isManifest = name.equals("manifest.json") || name.equals("study.json");
                boolean isImage = name.matches(IMAGE_NAME);
                int limit = isManifest ? MAX_JSON_BYTES : isImage ? MAX_IMAGE_BYTES : Integer.MAX_VALUE;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_TOTAL_BYTES || out.size() + (long) n > limit) {
                        throw new IOException("The study pack ZIP is larger than the safe limit.");
                    }
                    if (isManifest || isImage) out.write(buffer, 0, n);
                }
                if (isManifest) manifest = out.toByteArray();
                if (isImage) images.put(name, out.toByteArray());
            }
        }
        if (manifest == null) throw new IOException("The study pack ZIP must contain manifest.json.");
        return parse(text(manifest), images);
    }

    private static String text(byte[] data) {
        String value = new String(data, StandardCharsets.UTF_8);
        // Drop a UTF-8 byte-order mark (U+FEFF) that some editors add.
        return !value.isEmpty() && value.charAt(0) == 0xFEFF ? value.substring(1) : value;
    }

    static StudyPack parse(String json, Map<String, byte[]> images) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            int version = root.optInt("schemaVersion", 2);
            if (version != 1 && version != 2) {
                throw new IOException("Unsupported study pack version " + version + ". This app reads versions 1 and 2.");
            }
            JSONObject video = root.optJSONObject("video");
            String title = limit(root.optString("title",
                    video == null ? "" : video.optString("title", "")).trim(), 120);
            if (title.isEmpty()) title = "Study pack";
            long duration = Math.max(0, root.optLong("durationMs", video == null ? 0 : video.optLong("durationMs", 0)));
            String language = limit(root.optString("language", ""), 16);

            List<Overlay> overlays = new ArrayList<>();
            JSONArray drawn = root.has("overlays") ? root.getJSONArray("overlays") : root.optJSONArray("cues");
            if (drawn != null) {
                if (drawn.length() > MAX_OVERLAYS) throw new IOException("The study pack has too many drawings.");
                for (int i = 0; i < drawn.length(); i++) {
                    Overlay overlay = Overlay.parse(drawn.getJSONObject(i), i);
                    if (overlay.type.equals("image") && !images.containsKey(overlay.asset)) {
                        throw new IOException("Drawing " + (i + 1) + " needs the missing image " + overlay.asset + ".");
                    }
                    overlays.add(overlay);
                }
            }
            overlays.sort((a, b) -> Long.compare(a.startMs, b.startMs));

            List<Question> questions = new ArrayList<>();
            JSONArray asked = root.optJSONArray("questions");
            if (asked != null) {
                if (asked.length() > MAX_QUESTIONS) throw new IOException("The study pack has too many questions.");
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < asked.length(); i++) {
                    Question question = Question.parse(asked.getJSONObject(i), i);
                    if (!ids.add(question.id)) throw new JSONException("Question id " + question.id + " is used twice");
                    questions.add(question);
                }
            }
            questions.sort((a, b) -> Long.compare(a.askAtMs, b.askAtMs));

            List<Caption> captions = new ArrayList<>();
            JSONArray spoken = root.optJSONArray("captions");
            if (spoken != null) {
                if (spoken.length() > MAX_CAPTIONS) throw new IOException("The study pack has too many captions.");
                for (int i = 0; i < spoken.length(); i++) captions.add(Caption.parse(spoken.getJSONObject(i), i));
            }
            captions.sort((a, b) -> Long.compare(a.startMs, b.startMs));

            List<Narration> narration = new ArrayList<>();
            JSONArray spokenOver = root.optJSONArray("narration");
            if (spokenOver != null) {
                if (spokenOver.length() > MAX_NARRATION) throw new IOException("The study pack has too many voice-over lines.");
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < spokenOver.length(); i++) {
                    Narration line = Narration.parse(spokenOver.getJSONObject(i), i);
                    if (!ids.add(line.id)) throw new JSONException("Voice-over id " + line.id + " is used twice");
                    narration.add(line);
                }
            }
            narration.sort((a, b) -> Long.compare(a.startMs, b.startMs));

            if (overlays.isEmpty() && questions.isEmpty() && captions.isEmpty() && narration.isEmpty()) {
                throw new IOException("The study pack has no drawings, questions, captions or voice-over.");
            }
            return new StudyPack(title, language, duration, overlays, questions, captions, narration, images);
        } catch (JSONException error) {
            throw new IOException("This file is not a valid study pack: " + error.getMessage(), error);
        }
    }

    /** Captions active at a time, joined (usually one). */
    String captionAt(long timeMs) {
        StringBuilder text = new StringBuilder();
        for (Caption caption : captions) {
            if (caption.startMs > timeMs) break;
            if (timeMs < caption.endMs) {
                if (text.length() > 0) text.append('\n');
                text.append(caption.text);
            }
        }
        return text.toString();
    }

    static String limit(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    static long time(JSONObject json, String key) throws JSONException {
        long value = json.getLong(key);
        if (value < 0 || value > MAX_TIME_MS) throw new JSONException(key + " is outside the video");
        return value;
    }

    static String text(JSONObject json, String key, int max, boolean required, String what) throws JSONException {
        String value = json.optString(key, "").trim();
        if (required && value.isEmpty()) throw new JSONException(what + " needs \"" + key + "\"");
        if (value.length() > max) throw new JSONException(what + " " + key + " is longer than " + max + " characters");
        return value;
    }

    /** Normalized position: (x, y) and, for arrows/boxes/images, a second point (x2, y2). */
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

        Keyframe(long timeMs, Position position) {
            this.timeMs = timeMs;
            this.position = position;
        }
    }

    /** A drawing shown between startMs and endMs, optionally moving through keyframes. */
    static final class Overlay {
        static final List<String> TYPES = Collections.unmodifiableList(java.util.Arrays.asList(
                "arrow", "ring", "box", "spotlight", "label", "note", "image"));
        static final List<String> ANIMATIONS = Collections.unmodifiableList(java.util.Arrays.asList(
                "none", "fade", "pop", "draw", "pulse"));

        final String id, type, text, color, asset, animation;
        final long startMs, endMs;
        final Position position;
        final List<Keyframe> keyframes;

        private Overlay(String id, String type, String text, String color, String asset, String animation,
                        long startMs, long endMs, Position position, List<Keyframe> keyframes) {
            this.id = id; this.type = type; this.text = text; this.color = color; this.asset = asset;
            this.animation = animation; this.startMs = startMs; this.endMs = endMs;
            this.position = position;
            this.keyframes = Collections.unmodifiableList(keyframes);
        }

        static Overlay parse(JSONObject json, int index) throws JSONException {
            String what = "Drawing " + (index + 1);
            String type = json.getString("type").trim().toLowerCase(Locale.ROOT);
            if (!TYPES.contains(type)) throw new JSONException(what + " has unknown type \"" + type + "\"");
            long start = time(json, "startMs");
            long end = time(json, "endMs");
            if (end <= start) throw new JSONException(what + " ends before it starts");
            String text = text(json, "text", 600, type.equals("label") || type.equals("note"), what);
            String color = json.optString("color", type.equals("spotlight") ? "#000000" : "#64E8BF");
            if (!color.matches("#[a-fA-F0-9]{6}")) throw new JSONException(what + " color must look like #64E8BF");
            String asset = json.optString("asset", "");
            if (type.equals("image") && !asset.matches(IMAGE_NAME)) {
                throw new JSONException(what + " must reference images/name.png");
            }
            String animation = json.optString("animation", defaultAnimation(type)).trim().toLowerCase(Locale.ROOT);
            if (!ANIMATIONS.contains(animation)) throw new JSONException(what + " has unknown animation \"" + animation + "\"");
            Position base = Position.from(json, new Position(0.5f, 0.5f, 0.62f, 0.6f));
            List<Keyframe> keys = new ArrayList<>();
            JSONArray frames = json.optJSONArray("keyframes");
            if (frames != null) {
                if (frames.length() > 300) throw new JSONException(what + " has more than 300 keyframes");
                long last = -1;
                for (int i = 0; i < frames.length(); i++) {
                    JSONObject frame = frames.getJSONObject(i);
                    long at = time(frame, "timeMs");
                    if (at < start || at > end || at <= last) {
                        throw new JSONException(what + " keyframes must be in order and inside its time range");
                    }
                    last = at;
                    keys.add(new Keyframe(at, Position.from(frame, base)));
                }
            }
            String id = limit(json.optString("id", type + "-" + start + "-" + index), 64);
            return new Overlay(id, type, text, color.toUpperCase(Locale.ROOT), asset, animation,
                    start, end, base, keys);
        }

        static String defaultAnimation(String type) {
            switch (type) {
                case "arrow": return "draw";
                case "ring": return "pulse";
                case "label": case "note": return "pop";
                default: return "fade";
            }
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

    /** A multiple-choice question about the part of the video between startMs and endMs. */
    static final class Question {
        final String id, question, explanation;
        final List<String> options;
        final int answer;
        final long startMs, endMs, askAtMs;

        private Question(String id, String question, List<String> options, int answer, String explanation,
                         long startMs, long endMs, long askAtMs) {
            this.id = id; this.question = question; this.options = Collections.unmodifiableList(options);
            this.answer = answer; this.explanation = explanation;
            this.startMs = startMs; this.endMs = endMs; this.askAtMs = askAtMs;
        }

        static Question parse(JSONObject json, int index) throws JSONException {
            String what = "Question " + (index + 1);
            long start = time(json, "startMs");
            long end = time(json, "endMs");
            if (end <= start) throw new JSONException(what + " ends before it starts");
            long askAt = json.has("askAtMs") ? time(json, "askAtMs") : end;
            if (askAt < start) throw new JSONException(what + " is asked before its part of the video starts");
            String question = text(json, "question", 500, true, what);
            JSONArray choices = json.getJSONArray("options");
            if (choices.length() < 2 || choices.length() > 6) throw new JSONException(what + " needs 2 to 6 options");
            List<String> options = new ArrayList<>();
            for (int i = 0; i < choices.length(); i++) {
                String option = choices.getString(i).trim();
                if (option.isEmpty() || option.length() > 200) {
                    throw new JSONException(what + " option " + (i + 1) + " must be 1 to 200 characters");
                }
                options.add(option);
            }
            int answer = json.getInt("answer");
            if (answer < 0 || answer >= options.size()) {
                throw new JSONException(what + " answer must be the 0-based index of a listed option");
            }
            String explanation = text(json, "explanation", 1000, false, what);
            String id = limit(json.optString("id", "q" + (index + 1)), 64);
            return new Question(id, question, options, answer, explanation, start, end, askAt);
        }
    }

    /**
     * One voice-over line, spoken from startMs. The video keeps playing while it
     * speaks; if the line is still speaking at endMs (or at the next line's start)
     * the video waits for it. voice and speed override the player's settings.
     */
    static final class Narration {
        final String id, text, voice;
        final long startMs, endMs;
        final float speed;

        private Narration(String id, String text, long startMs, long endMs, String voice, float speed) {
            this.id = id; this.text = text; this.startMs = startMs; this.endMs = endMs;
            this.voice = voice; this.speed = speed;
        }

        static Narration parse(JSONObject json, int index) throws JSONException {
            String what = "Voice-over line " + (index + 1);
            long start = time(json, "startMs");
            long end = json.has("endMs") ? time(json, "endMs") : -1;
            if (end != -1 && end <= start) throw new JSONException(what + " ends before it starts");
            String text = text(json, "text", 2000, true, what);
            String voice = json.optString("voice", "").trim().toUpperCase(Locale.ROOT);
            if (!voice.isEmpty() && !voice.matches("[FM][1-5]")) throw new JSONException(what + " voice must be F1-F5 or M1-M5");
            float speed = 0f;
            if (json.has("speed")) {
                double value = json.getDouble("speed");
                if (!(value >= 0.7 && value <= 2.0)) throw new JSONException(what + " speed must be 0.7 to 2.0");
                speed = (float) value;
            }
            String id = limit(json.optString("id", "v" + (index + 1)), 64);
            return new Narration(id, text, start, end, voice, speed);
        }
    }

    static final class Caption {
        final long startMs, endMs;
        final String text;

        private Caption(long startMs, long endMs, String text) {
            this.startMs = startMs; this.endMs = endMs; this.text = text;
        }

        static Caption parse(JSONObject json, int index) throws JSONException {
            String what = "Caption " + (index + 1);
            long start = time(json, "startMs");
            long end = time(json, "endMs");
            if (end <= start) throw new JSONException(what + " ends before it starts");
            return new Caption(start, end, text(json, "text", 300, true, what));
        }
    }
}
