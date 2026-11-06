package app.streamsave;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * The Supertonic 3 voice model, downloaded once on request directly from
 * Supertone's Hugging Face repository at one pinned revision (the revision
 * the official SDK 1.3.1 pins). Every file is checked against the size and
 * SHA-256 recorded here, so a changed upstream model can never be loaded.
 * The model is licensed by Supertone Inc. under OpenRAIL-M; StreamSave does
 * not redistribute it.
 *
 * Plain Java (paths are supplied by the caller) so it is unit-tested on the JVM.
 */
final class VoiceModel {
    static final String REPO = "Supertone/supertonic-3";
    static final String REVISION = "724fb5abbf5502583fb520898d45929e62f02c0b";
    static final String LICENSE_URL = "https://huggingface.co/" + REPO + "/blob/" + REVISION + "/LICENSE";
    static final List<String> VOICES = Collections.unmodifiableList(Arrays.asList(
            "F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"));
    private static final String MARKER = "verified-" + REVISION.substring(0, 12);

    static final class Asset {
        final String path, sha256;
        final long size;

        Asset(String path, long size, String sha256) {
            this.path = path; this.size = size; this.sha256 = sha256;
        }

        URL url() throws IOException {
            return new URL("https://huggingface.co/" + REPO + "/resolve/" + REVISION + "/" + path);
        }
    }

    static final List<Asset> ASSETS = Collections.unmodifiableList(Arrays.asList(
            new Asset("LICENSE", 15007, "0d944a9110fed9a9602d60e0423a272903e7bd21ab060490774efc77c2275e9f"),
            new Asset("onnx/tts.json", 8253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
            new Asset("onnx/unicode_indexer.json", 277676, "9bf7346e43883a81f8645c81224f786d43c5b57f3641f6e7671a7d6c493cb24f"),
            new Asset("onnx/duration_predictor.onnx", 3700147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
            new Asset("onnx/text_encoder.onnx", 36416150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
            new Asset("onnx/vector_estimator.onnx", 256534781, "883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c"),
            new Asset("onnx/vocoder.onnx", 101424195, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
            new Asset("voice_styles/F1.json", 292046, "bbdec6ee00231c2c742ad05483df5334cab3b52fda3ba38e6a07059c4563dbc2"),
            new Asset("voice_styles/F2.json", 292423, "7c722c6a72707b1a77f035d67f0d1351ba187738e06f7683e8c72b1df3477fc6"),
            new Asset("voice_styles/F3.json", 290794, "12f6ef2573baa2defa1128069cb59f203e3ab67c92af77b42df8a0e3a2f7c6ab"),
            new Asset("voice_styles/F4.json", 291808, "c2fa764c1225a76dfc3e2c73e8aa4f70d9ee48793860eb34c295fff01c2e032b"),
            new Asset("voice_styles/F5.json", 291479, "45966e73316415626cf41a7d1c6f3b4c70dbc1ba2bee5c1978ef0ce33244fc8d"),
            new Asset("voice_styles/M1.json", 291748, "e35604687f5d23694b8e91593a93eec0e4eca6c0b02bb8ed69139ab2ea6b0a5b"),
            new Asset("voice_styles/M2.json", 292055, "b76cbf62bac707c710cf0ae5aba5e31eea1a6339a9734bfae33ab98499534a50"),
            new Asset("voice_styles/M3.json", 290198, "ea1ac35ccb91b0d7ecad533a2fbd0eec10c91513d8951e3b25fbba99954e159b"),
            new Asset("voice_styles/M4.json", 291522, "ca8eefad4fcd989c9379032ff3e50738adc547eeb5e221b82593a6d7b3bac303"),
            new Asset("voice_styles/M5.json", 291469, "dd22b92740314321f8ae11c5e87f8dd60d060f15dd3a632b5adf77f471f77af2")));

    final File dir;

    VoiceModel(File root) { dir = new File(root, "supertonic3-" + REVISION.substring(0, 12)); }

    static long totalBytes() {
        long total = 0;
        for (Asset asset : ASSETS) total += asset.size;
        return total;
    }

    File onnxDir() { return new File(dir, "onnx"); }

    File voiceFile(String voice) {
        if (!VOICES.contains(voice)) throw new IllegalArgumentException("Unknown voice " + voice);
        return new File(dir, "voice_styles/" + voice + ".json");
    }

    File license() { return new File(dir, "LICENSE"); }

    /** True once every file was downloaded and verified; sizes are rechecked cheaply. */
    boolean installed() {
        if (!new File(dir, MARKER).isFile()) return false;
        for (Asset asset : ASSETS) {
            File file = new File(dir, asset.path);
            if (!file.isFile() || file.length() != asset.size) return false;
        }
        return true;
    }

    /** Downloads every missing file in parallel ranges, verifying SHA-256 before keeping it. */
    void download(LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create the voice folder.");
        long before = 0;
        for (Asset asset : ASSETS) {
            if (cancelled.getAsBoolean()) throw new IOException("Voice download cancelled.");
            File target = new File(dir, asset.path);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create the voice folder.");
            if (target.isFile() && target.length() == asset.size && sha256(target).equals(asset.sha256)) {
                before += asset.size;
                progress.accept(before);
                continue;
            }
            File partial = new File(target.getPath() + ".part");
            if (partial.exists() && !partial.delete()) throw new IOException("Cannot replace an unfinished voice file.");
            final long offset = before;
            URL url = ParallelDownload.resolve(asset.url(), VoiceModel::allowedHost);
            ParallelDownload.fetch(url, asset.size, partial, VoiceModel::allowedHost,
                    bytes -> progress.accept(offset + Math.max(0, bytes)), cancelled);
            if (!sha256(partial).equals(asset.sha256)) {
                if (!partial.delete()) partial.deleteOnExit();
                throw new IOException("A downloaded voice file did not match Supertone's checksum. Please try again.");
            }
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace a voice file.");
            if (!partial.renameTo(target)) throw new IOException("Cannot save the voice file.");
            before += asset.size;
            progress.accept(before);
        }
        if (!new File(dir, MARKER).createNewFile() && !new File(dir, MARKER).isFile()) {
            throw new IOException("Cannot finish installing the voice.");
        }
    }

    /** Removes the voice model and any unfinished download. */
    void delete() {
        deleteTree(dir);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    /** Model downloads may only touch Hugging Face over HTTPS (checked for every request and redirect). */
    static boolean allowedHost(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("huggingface.co") || host.endsWith(".huggingface.co") || host.endsWith(".hf.co");
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }
}
