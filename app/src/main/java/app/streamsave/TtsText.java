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
    /**
     * Exactly the characters Python's {@code \s} and {@code str.strip()} treat as whitespace
     * (str.isspace()), which the SDK relies on. Written out because Android rejects Java's
     * Unicode-character-class Pattern flag ("Unsupported flags: 256"; see AndroidCompatibilityTest),
     * and Java's own \s and String.strip() differ from Python's (non-breaking spaces, U+001C–U+001F).
     */
    static final String WS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029"
            + "\\u202F\\u205F\\u3000]";

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
    private static final Pattern WHITESPACE = Pattern.compile(WS + "+");
    private static final Pattern ENDING = Pattern.compile(
            "[.!?;:,'\"')\\]}…。」』】〉》›»]$");
    private static final Pattern PARAGRAPHS = Pattern.compile("\\n" + WS + "*\\n+");
    private static final Pattern SENTENCES = Pattern.compile(
            "(?<!Mr\\.)(?<!Mrs\\.)(?<!Ms\\.)(?<!Dr\\.)(?<!Prof\\.)(?<!Sr\\.)(?<!Jr\\.)(?<!Ph\\.D\\.)"
                    + "(?<!etc\\.)(?<!e\\.g\\.)(?<!i\\.e\\.)(?<!vs\\.)(?<!Inc\\.)(?<!Ltd\\.)(?<!Co\\.)"
                    + "(?<!Corp\\.)(?<!St\\.)(?<!Ave\\.)(?<!Blvd\\.)(?<!\\b[A-Z]\\.)(?<=[.!?])" + WS + "+");

    private TtsText() { }

    /** Python's str.isspace() for one character (all such characters are in the BMP). */
    static boolean isSpace(char c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000;
    }

    /** Python's str.strip() with no arguments. */
    static String strip(String text) {
        int start = 0, end = text.length();
        while (start < end && isSpace(text.charAt(start))) start++;
        while (end > start && isSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    /** Normalizes text and wraps it in language tokens, e.g. "<en>Hello.</en>". */
    static String preprocess(String text, String lang) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKD);
        value = EMOJI.matcher(value).replaceAll("");
        for (Map.Entry<String, String> symbol : SYMBOLS.entrySet()) value = value.replace(symbol.getKey(), symbol.getValue());
        value = SPECIAL.matcher(value).replaceAll("");
        for (String[] expansion : EXPANSIONS) value = value.replace(expansion[0], expansion[1]);
        for (String[] spacing : PUNCTUATION_SPACING) value = value.replace(spacing[0], spacing[1]);
        value = DUPLICATE_QUOTES.matcher(value).replaceAll("$1");
        value = strip(WHITESPACE.matcher(value).replaceAll(" "));
        if (!ENDING.matcher(value).find()) value = value + ".";
        return lang == null ? value : "<" + lang + ">" + value + "</" + lang + ">";
    }

    /** Splits text into paragraphs, then sentences, joined into chunks of at most maxLength characters. */
    static List<String> chunk(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : PARAGRAPHS.split(strip(text))) {
            paragraph = strip(paragraph);
            if (paragraph.isEmpty()) continue;
            StringBuilder current = new StringBuilder();
            for (String sentence : SENTENCES.split(paragraph, -1)) {
                String piece = strip(sentence);
                if (piece.isEmpty()) continue;
                if (current.length() + piece.length() + 1 <= maxLength) {
                    if (current.length() > 0) current.append(' ');
                    current.append(piece);
                } else {
                    if (current.length() > 0) chunks.add(strip(current.toString()));
                    current = new StringBuilder(piece);
                }
            }
            if (current.length() > 0 && !strip(current.toString()).isEmpty()) chunks.add(strip(current.toString()));
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
