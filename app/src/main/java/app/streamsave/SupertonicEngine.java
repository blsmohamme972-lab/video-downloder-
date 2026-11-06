package app.streamsave;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * On-device speech with Supertone's Supertonic 3 model (OpenRAIL-M; the model
 * files are downloaded from Supertone by VoiceModel, never shipped here).
 * The pipeline follows the official SDK (supertonic 1.3.1, MIT): duration
 * predictor, text encoder, a few flow-matching steps of the vector estimator,
 * then the vocoder; long text is spoken in sentence chunks joined by 0.3 s of
 * silence.
 *
 * No Android types, so the same class runs in a desktop JVM for the parity
 * check against the Python SDK.
 */
final class SupertonicEngine implements AutoCloseable {
    static final float MIN_SPEED = 0.7f;
    static final float MAX_SPEED = 2.0f;
    static final float SILENCE_SECONDS = 0.3f;

    /** Standard normal samples; the app uses java.util.Random, the parity check replays the SDK's. */
    interface Noise { float next(); }

    static final class Voice {
        final float[] ttl, dp;
        final long[] ttlShape, dpShape;

        Voice(float[] ttl, long[] ttlShape, float[] dp, long[] dpShape) {
            this.ttl = ttl; this.ttlShape = ttlShape; this.dp = dp; this.dpShape = dpShape;
        }
    }

    final int sampleRate;
    private final int baseChunkSize, compressFactor, latentDim;
    private final int[] indexer;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession durationPredictor, textEncoder, vectorEstimator, vocoder;

    SupertonicEngine(File onnxDir, int threads) throws IOException, OrtException {
        try {
            JSONObject config = new JSONObject(read(new File(onnxDir, "tts.json")));
            sampleRate = config.getJSONObject("ae").getInt("sample_rate");
            baseChunkSize = config.getJSONObject("ae").getInt("base_chunk_size");
            compressFactor = config.getJSONObject("ttl").getInt("chunk_compress_factor");
            latentDim = config.getJSONObject("ttl").getInt("latent_dim") * compressFactor;
        } catch (JSONException error) {
            throw new IOException("The voice model settings are damaged. Remove and download the voice again.", error);
        }
        indexer = parseIndexer(read(new File(onnxDir, "unicode_indexer.json")));
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        durationPredictor = env.createSession(new File(onnxDir, "duration_predictor.onnx").getPath(), options);
        textEncoder = env.createSession(new File(onnxDir, "text_encoder.onnx").getPath(), options);
        vectorEstimator = env.createSession(new File(onnxDir, "vector_estimator.onnx").getPath(), options);
        vocoder = env.createSession(new File(onnxDir, "vocoder.onnx").getPath(), options);
    }

    int[] indexer() { return indexer; }

    static Voice loadVoice(File json) throws IOException {
        try {
            JSONObject root = new JSONObject(read(json));
            JSONObject ttl = root.getJSONObject("style_ttl"), dp = root.getJSONObject("style_dp");
            return new Voice(flatten(ttl.get("data")), shape(ttl.getJSONArray("dims")),
                    flatten(dp.get("data")), shape(dp.getJSONArray("dims")));
        } catch (JSONException error) {
            throw new IOException("The voice file " + json.getName() + " is damaged.", error);
        }
    }

    /** Speaks text: returns 32-bit float samples at sampleRate, or null if cancelled. */
    float[] synthesize(String text, Voice voice, String lang, int steps, float speed, Noise noise,
                       BooleanSupplier cancelled) throws OrtException {
        if (speed < MIN_SPEED || speed > MAX_SPEED) throw new IllegalArgumentException("Speed must be 0.7 to 2.0");
        List<float[]> parts = new ArrayList<>();
        for (String chunk : TtsText.chunk(text, TtsText.MAX_CHUNK_LENGTH)) {
            if (cancelled.getAsBoolean()) return null;
            float[] wav = synthesizeChunk(chunk, voice, lang, steps, speed, noise, cancelled);
            if (wav == null) return null;
            parts.add(wav);
        }
        int silence = (int) (SILENCE_SECONDS * sampleRate);
        int total = 0;
        for (float[] part : parts) total += part.length;
        total += Math.max(0, parts.size() - 1) * silence;
        float[] out = new float[total];
        int at = 0;
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, out, at, parts.get(i).length);
            at += parts.get(i).length + (i < parts.size() - 1 ? silence : 0);
        }
        return out;
    }

    private float[] synthesizeChunk(String chunk, Voice voice, String lang, int steps, float speed, Noise noise,
                                    BooleanSupplier cancelled) throws OrtException {
        long[] ids = TtsText.ids(TtsText.preprocess(chunk, lang), indexer);
        if (ids.length == 0) return new float[0];
        long[] textShape = {1, ids.length};
        float[] ones = new float[ids.length];
        java.util.Arrays.fill(ones, 1f);
        try (OnnxTensor textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), textShape);
             OnnxTensor textMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(ones), new long[]{1, 1, ids.length});
             OnnxTensor styleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.dp), voice.dpShape);
             OnnxTensor styleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(voice.ttl), voice.ttlShape)) {

            float duration;
            Map<String, OnnxTensor> dpInputs = new HashMap<>();
            dpInputs.put("text_ids", textIds);
            dpInputs.put("style_dp", styleDp);
            dpInputs.put("text_mask", textMask);
            try (OrtSession.Result result = durationPredictor.run(dpInputs)) {
                duration = ((OnnxTensor) result.get(0)).getFloatBuffer().get(0) / speed;
            }

            Map<String, OnnxTensor> encInputs = new HashMap<>();
            encInputs.put("text_ids", textIds);
            encInputs.put("style_ttl", styleTtl);
            encInputs.put("text_mask", textMask);
            try (OrtSession.Result encoded = textEncoder.run(encInputs)) {
                OnnxTensor textEmb = (OnnxTensor) encoded.get(0);

                // Same arithmetic as the SDK's sample_noisy_latent / get_latent_mask.
                int chunkSize = baseChunkSize * compressFactor;
                float wavLenMax = duration * sampleRate;
                int latentLen = (int) ((wavLenMax + chunkSize - 1) / chunkSize);
                long wavLength = (long) (duration * sampleRate);
                long maskedLen = (wavLength + chunkSize - 1) / chunkSize;
                float[] mask = new float[latentLen];
                for (int i = 0; i < latentLen; i++) mask[i] = i < maskedLen ? 1f : 0f;
                float[] latent = new float[latentDim * latentLen];
                for (int d = 0; d < latentDim; d++) {
                    for (int t = 0; t < latentLen; t++) latent[d * latentLen + t] = noise.next() * mask[t];
                }
                long[] latentShape = {1, latentDim, latentLen};
                try (OnnxTensor latentMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, latentLen});
                     OnnxTensor totalStep = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{steps}), new long[]{1})) {
                    for (int step = 0; step < steps; step++) {
                        if (cancelled.getAsBoolean()) return null;
                        try (OnnxTensor noisy = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape);
                             OnnxTensor current = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{step}), new long[]{1})) {
                            Map<String, OnnxTensor> inputs = new HashMap<>();
                            inputs.put("noisy_latent", noisy);
                            inputs.put("text_emb", textEmb);
                            inputs.put("style_ttl", styleTtl);
                            inputs.put("text_mask", textMask);
                            inputs.put("latent_mask", latentMask);
                            inputs.put("current_step", current);
                            inputs.put("total_step", totalStep);
                            try (OrtSession.Result next = vectorEstimator.run(inputs)) {
                                FloatBuffer buffer = ((OnnxTensor) next.get(0)).getFloatBuffer();
                                buffer.get(latent, 0, Math.min(latent.length, buffer.remaining()));
                            }
                        }
                    }
                    try (OnnxTensor finalLatent = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), latentShape);
                         OrtSession.Result audio = vocoder.run(java.util.Collections.singletonMap("latent", finalLatent))) {
                        FloatBuffer buffer = ((OnnxTensor) audio.get(0)).getFloatBuffer();
                        float[] wav = new float[buffer.remaining()];
                        buffer.get(wav);
                        return wav;
                    }
                }
            }
        }
    }

    @Override public void close() {
        for (OrtSession session : new OrtSession[]{durationPredictor, textEncoder, vectorEstimator, vocoder}) {
            try {
                if (session != null) session.close();
            } catch (OrtException ignored) { }
        }
    }

    // ----- small helpers -----

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** unicode_indexer.json is one flat JSON array of 65536 integers; parsed without building a JSONArray. */
    static int[] parseIndexer(String json) throws IOException {
        String body = json.trim();
        if (!body.startsWith("[") || !body.endsWith("]")) throw new IOException("The voice character table is damaged.");
        String[] parts = body.substring(1, body.length() - 1).split(",");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException error) {
            throw new IOException("The voice character table is damaged.", error);
        }
        if (out.length < 128) throw new IOException("The voice character table is incomplete.");
        return out;
    }

    private static long[] shape(JSONArray dims) throws JSONException {
        long[] out = new long[dims.length()];
        for (int i = 0; i < out.length; i++) out[i] = dims.getLong(i);
        return out;
    }

    private static float[] flatten(Object data) throws JSONException {
        List<Float> values = new ArrayList<>();
        collect(data, values);
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static void collect(Object value, List<Float> out) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.get(i), out);
        } else {
            out.add(((Number) value).floatValue());
        }
    }
}
