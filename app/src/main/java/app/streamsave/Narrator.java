package app.streamsave;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns a study pack's voice-over lines into speech on the phone with the
 * Supertonic voice model, in the background and in the order they are needed
 * (the lines just ahead of playback first). Each line is saved as a 16-bit
 * WAV keyed by its text and voice settings, in the app's own storage (Android
 * does not clear it), so settings changes regenerate only what changed and
 * reopening a video costs nothing.
 *
 * The Study player shows what it is doing (starting, which line, how long a
 * line takes) and can pause or resume it.
 */
final class Narrator {
    static final String DEFAULT_VOICE = "F1";
    static final int DEFAULT_STEPS = 12;
    static final float DEFAULT_SPEED = 1.30f;
    static final List<String> LANGUAGES = Arrays.asList("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es",
            "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl",
            "sv", "tr", "uk", "vi");
    private static final long CACHE_LIMIT_BYTES = 400L * 1024 * 1024;

    interface Listener {
        /** A line is ready to play, or preparation progressed. */
        void onPrepared();
        /** The voice could not be created; voice-over stops for this video. */
        void onFailed(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "StreamSave-Voice");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<String> failed = new HashSet<>();
    /** Which lines of the current plan have audio for the current settings (kept fresh by the worker). */
    private final Map<StudyPack.Narration, Boolean> ready = new ConcurrentHashMap<>();
    private final VoiceModel model;
    private final File cache;
    private final Listener listener;
    private SupertonicEngine engine;
    private volatile NarrationPlan plan;
    private volatile String lang = "en";
    private volatile String voice = DEFAULT_VOICE;
    private volatile int steps = DEFAULT_STEPS;
    private volatile float speed = DEFAULT_SPEED;
    private volatile long position;
    private volatile StudyPack.Narration urgent;
    private volatile boolean closed, paused, starting;
    private volatile StudyPack.Narration working;
    private volatile long lastLineMillis, workingSince;
    private volatile String failure = "";

    /** @param folder where finished lines are kept (created when needed) */
    Narrator(VoiceModel model, File folder, Listener listener) {
        this.model = model;
        this.cache = folder;
        this.listener = listener;
    }

    /** Stops preparing new lines (the current one is abandoned) until resumed; finished lines stay. */
    void setPaused(boolean pause) {
        paused = pause;
        if (pause) generation.incrementAndGet();
        else restart();
    }

    boolean paused() { return paused; }

    /** True while the voice model is being loaded into memory (a few seconds, once per video). */
    boolean starting() { return starting; }

    /** 1-based number of the line being created now, or 0. */
    int workingNumber() {
        StudyPack.Narration line = working;
        NarrationPlan current = plan;
        if (line == null || current == null) return 0;
        int index = current.lines().indexOf(line);
        return index < 0 ? 0 : index + 1;
    }

    /** How long the line being created has taken so far, in milliseconds (0 when idle). */
    long workingMillis() {
        return working == null ? 0 : android.os.SystemClock.elapsedRealtime() - workingSince;
    }

    /** How long the last finished line took to create, in milliseconds (0 before the first). */
    long lastLineMillis() { return lastLineMillis; }

    /** Why the voice-over stopped, or "" while it works. */
    String failure() { return failure; }

    /** Tries again after a failure: lines that failed are attempted once more. */
    void retry() {
        failure = "";
        synchronized (failed) { failed.clear(); }
        paused = false;
        restart();
    }

    boolean available() { return model.installed(); }

    void setSettings(String voice, int steps, float speed) {
        this.voice = VoiceModel.VOICES.contains(voice) ? voice : DEFAULT_VOICE;
        this.steps = Math.max(1, Math.min(32, steps));
        this.speed = Math.max(SupertonicEngine.MIN_SPEED, Math.min(SupertonicEngine.MAX_SPEED, speed));
        restart();
    }

    /** Starts preparing a pack's lines (null stops). Language falls back to English. */
    void setPlan(NarrationPlan plan, String language) {
        this.plan = plan;
        this.lang = language != null && LANGUAGES.contains(language.toLowerCase(Locale.ROOT))
                ? language.toLowerCase(Locale.ROOT) : "en";
        restart();
    }

    void setPosition(long positionMs) { position = positionMs; }

    /** Prepares this line before anything else. */
    void prioritize(StudyPack.Narration line) {
        urgent = line;
        restart();
    }

    /** The finished audio for a line, or null while it is still being prepared. */
    File clip(StudyPack.Narration line) {
        if (!Boolean.TRUE.equals(ready.get(line))) return null;
        File file = fileFor(line);
        if (file.isFile()) return file;
        ready.put(line, false); // the cache was cleared by Android
        restart();
        return null;
    }

    int readyCount() {
        NarrationPlan current = plan;
        if (current == null) return 0;
        int count = 0;
        for (StudyPack.Narration line : current.lines()) if (Boolean.TRUE.equals(ready.get(line))) count++;
        return count;
    }

    /** Video time up to which the voice-over is ready, from positionMs on (see NarrationPlan.readyUntil). */
    long readyUntil(long positionMs, long videoEndMs) {
        NarrationPlan current = plan;
        if (current == null) return 0;
        return current.readyUntil(positionMs, videoEndMs, line -> Boolean.TRUE.equals(ready.get(line)));
    }

    int total() {
        NarrationPlan current = plan;
        return current == null ? 0 : current.lines().size();
    }

    /** Stops work and frees the model's memory (about 0.5 GB) right away. */
    void release() {
        closed = true;
        generation.incrementAndGet();
        worker.execute(this::closeEngine);
        worker.shutdown();
    }

    /** Frees the model's memory, for example before deleting the model files. */
    void unload() {
        generation.incrementAndGet();
        worker.execute(this::closeEngine);
    }

    private void restart() {
        if (closed) return;
        final int run = generation.incrementAndGet();
        worker.execute(() -> work(run));
    }

    private void work(int run) {
        // Settings or the pack may have changed: re-check which lines already have audio.
        NarrationPlan current = plan;
        ready.clear();
        if (current != null) {
            for (StudyPack.Narration line : current.lines()) ready.put(line, fileFor(line).isFile());
            main.post(listener::onPrepared);
        }
        while (!closed && !paused && run == generation.get()) {
            StudyPack.Narration next = pickNext();
            if (next == null) return;
            String key = keyFor(next);
            try {
                if (engine == null) {
                    if (!model.installed()) return;
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
                    starting = true;
                    main.post(listener::onPrepared);
                    try {
                        engine = new SupertonicEngine(model.onnxDir(), threads);
                    } finally {
                        starting = false;
                    }
                }
                String lineVoice = next.voice.isEmpty() ? voice : next.voice;
                float lineSpeed = next.speed > 0 ? next.speed : speed;
                SupertonicEngine.Voice style = SupertonicEngine.loadVoice(model.voiceFile(lineVoice));
                Random random = new Random();
                long began = android.os.SystemClock.elapsedRealtime();
                workingSince = began;
                working = next;
                main.post(listener::onPrepared);
                float[] wav;
                try {
                    wav = engine.synthesize(next.text, style, lang, steps, lineSpeed,
                            () -> (float) random.nextGaussian(), () -> closed || paused || run != generation.get());
                } finally {
                    working = null;
                }
                if (wav == null) return; // cancelled; a newer run continues
                lastLineMillis = android.os.SystemClock.elapsedRealtime() - began;
                writeWav(fileFor(next), wav, engine.sampleRate);
                ready.put(next, true);
                failure = "";
                if (urgent == next) urgent = null;
                trimCache();
                main.post(listener::onPrepared);
            } catch (Throwable error) {
                synchronized (failed) { failed.add(key); }
                if (urgent == next) urgent = null;
                if (error instanceof OutOfMemoryError || engine == null) {
                    closeEngine();
                    String message = "The phone could not start the voice model ("
                            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + ").";
                    failure = message;
                    main.post(() -> listener.onFailed(message));
                    return;
                }
            }
        }
    }

    private StudyPack.Narration pickNext() {
        NarrationPlan current = plan;
        if (current == null) return null;
        StudyPack.Narration first = urgent;
        if (first != null && !Boolean.TRUE.equals(ready.get(first)) && !hasFailed(first)) return first;
        for (StudyPack.Narration line : current.preparationOrder(position)) {
            if (!Boolean.TRUE.equals(ready.get(line)) && !hasFailed(line)) return line;
        }
        return null;
    }

    private boolean hasFailed(StudyPack.Narration line) {
        synchronized (failed) { return failed.contains(keyFor(line)); }
    }

    private void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private File fileFor(StudyPack.Narration line) { return new File(cache, keyFor(line) + ".wav"); }

    private String keyFor(StudyPack.Narration line) {
        String lineVoice = line.voice.isEmpty() ? voice : line.voice;
        float lineSpeed = line.speed > 0 ? line.speed : speed;
        String identity = VoiceModel.REVISION + "|" + lineVoice + "|" + steps + "|"
                + String.format(Locale.US, "%.3f", lineSpeed) + "|" + lang + "|" + line.text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 16-bit mono PCM WAV, written to a temporary file and renamed when complete. */
    private void writeWav(File target, float[] samples, int rate) throws IOException {
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create the voice cache.");
        File temporary = new File(target.getPath() + ".tmp");
        int dataBytes = samples.length * 2;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary), 64 * 1024)) {
            out.write(new byte[]{'R', 'I', 'F', 'F'});
            le32(out, 36 + dataBytes);
            out.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            le32(out, 16);
            le16(out, 1);            // PCM
            le16(out, 1);            // mono
            le32(out, rate);
            le32(out, rate * 2);     // byte rate
            le16(out, 2);            // block align
            le16(out, 16);           // bits per sample
            out.write(new byte[]{'d', 'a', 't', 'a'});
            le32(out, dataBytes);
            for (float sample : samples) {
                int value = Math.round(Math.max(-1f, Math.min(1f, sample)) * 32767f);
                le16(out, value);
            }
        }
        if (!temporary.renameTo(target)) {
            if (!temporary.delete()) temporary.deleteOnExit();
            throw new IOException("Cannot save the voice-over.");
        }
    }

    private void trimCache() {
        File[] files = cache.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (total <= CACHE_LIMIT_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (total <= CACHE_LIMIT_BYTES) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }

    private static void le16(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void le32(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }
}
