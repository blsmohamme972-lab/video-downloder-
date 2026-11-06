package app.streamsave;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text preparation for the Supertonic voice model: the same normalization,
 * sentence chunking and character indexing as Supertone's official Python SDK
 * (supertonic 1.3.1, MIT licensed, core.py and utils.py), ported to Java.
 * TtsTextTest checks it against outputs recorded from that SDK.
 *
 * Plain Java so it is unit-tested on the JVM.
 */
final class TtsText {
    static final int MAX_CHUNK_LENGTH = 300;
    private static final int UNICODE_FLAGS = Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern EMOJI = Pattern.compile("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}"
            + "\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}"
            + "\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}"
            + "\\x{2700}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}]+");
    private static final Map<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("–", "-");
        SYMBOLS.put("‑", "-");
        SYMBOLS.put("—", "-");
        SYMBOLS.put("¯", " ");
        SYMBOLS.put("_", " ");
        SYMBOLS.put("“", "\"");
        SYMBOLS.put("”", "\"");
        SYMBOLS.put("‘", "'");
        SYMBOLS.put("’", "'");
        SYMBOLS.put("´", "'");
        SYMBOLS.put("`", "'");
        SYMBOLS.put("[", " ");
        SYMBOLS.put("]", " ");
        SYMBOLS.put("|", " ");
        SYMBOLS.put("/", " ");
        SYMBOLS.put("#", " ");
        SYMBOLS.put("→", " ");
        SYMBOLS.put("←", " ");
    }
    private static final Pattern SPECIAL = Pattern.compile("[♥☆♡©\\\\]");
    private static final String[][] EXPANSIONS = {
            {"@", " at "}, {"e.g.,", "for example, "}, {"i.e.,", "that is, "}};
    private static final String[][] PUNCTUATION_SPACING = {
            {" ,", ","}, {" .", "."}, {" !", "!"}, {" ?", "?"}, {" ;", ";"}, {" :", ":"}, {" '", "'"}};
    private static final Pattern DUPLICATE_QUOTES = Pattern.compile("([\"'`])\\1+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", UNICODE_FLAGS);
    private static final Pattern ENDING = Pattern.compile(
            "[.!?;:,'\"')\\]}…。」』】〉》›»]$");
    private static final Pattern PARAGRAPHS = Pattern.compile("\\n\\s*\\n+", UNICODE_FLAGS);
    private static final Pattern SENTENCES = Pattern.compile(
            "(?<!Mr\\.)(?<!Mrs\\.)(?<!Ms\\.)(?<!Dr\\.)(?<!Prof\\.)(?<!Sr\\.)(?<!Jr\\.)(?<!Ph\\.D\\.)"
                    + "(?<!etc\\.)(?<!e\\.g\\.)(?<!i\\.e\\.)(?<!vs\\.)(?<!Inc\\.)(?<!Ltd\\.)(?<!Co\\.)"
                    + "(?<!Corp\\.)(?<!St\\.)(?<!Ave\\.)(?<!Blvd\\.)(?<!\\b[A-Z]\\.)(?<=[.!?])\\s+",
            UNICODE_FLAGS);

    private TtsText() { }

    /** Normalizes text and wraps it in language tokens, e.g. "<en>Hello.</en>". */
    static String preprocess(String text, String lang) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKD);
        value = EMOJI.matcher(value).replaceAll("");
        for (Map.Entry<String, String> symbol : SYMBOLS.entrySet()) value = value.replace(symbol.getKey(), symbol.getValue());
        value = SPECIAL.matcher(value).replaceAll("");
        for (String[] expansion : EXPANSIONS) value = value.replace(expansion[0], expansion[1]);
        for (String[] spacing : PUNCTUATION_SPACING) value = value.replace(spacing[0], spacing[1]);
        value = DUPLICATE_QUOTES.matcher(value).replaceAll("$1");
        value = WHITESPACE.matcher(value).replaceAll(" ").strip();
        if (!ENDING.matcher(value).find()) value = value + ".";
        return lang == null ? value : "<" + lang + ">" + value + "</" + lang + ">";
    }

    /** Splits text into paragraphs, then sentences, joined into chunks of at most maxLength characters. */
    static List<String> chunk(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : PARAGRAPHS.split(text.strip())) {
            paragraph = paragraph.strip();
            if (paragraph.isEmpty()) continue;
            StringBuilder current = new StringBuilder();
            for (String sentence : SENTENCES.split(paragraph, -1)) {
                String piece = sentence.strip();
                if (piece.isEmpty()) continue;
                if (current.length() + piece.length() + 1 <= maxLength) {
                    if (current.length() > 0) current.append(' ');
                    current.append(piece);
                } else {
                    if (current.length() > 0) chunks.add(current.toString().strip());
                    current = new StringBuilder(piece);
                }
            }
            if (current.length() > 0 && !current.toString().strip().isEmpty()) chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /**
     * Model input ids for preprocessed text. Characters the model has no id
     * for (indexer value -1) or outside the Basic Multilingual Plane are
     * dropped, where the SDK would refuse the whole text.
     */
    static long[] ids(String preprocessed, int[] indexer) {
        long[] out = new long[preprocessed.length()];
        int n = 0;
        int i = 0;
        while (i < preprocessed.length()) {
            int codePoint = preprocessed.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint >= indexer.length) continue;
            int id = indexer[codePoint];
            if (id >= 0) out[n++] = id;
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Characters in the text that the model cannot pronounce (for checking narration before publishing). */
    static String unsupported(String text, int[] indexer) {
        StringBuilder missing = new StringBuilder();
        String prepared = preprocess(text, null);
        for (int i = 0; i < prepared.length(); ) {
            int codePoint = prepared.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean known = codePoint < indexer.length && indexer[codePoint] >= 0;
            if (!known && missing.indexOf(new String(Character.toChars(codePoint))) < 0) missing.appendCodePoint(codePoint);
        }
        return missing.toString();
    }
}
