package app.streamsave;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Unit tests run on the desktop JVM, but the app runs on Android, whose java.util.regex is
 * built on ICU. Android throws IllegalArgumentException("Unsupported flags: ...") for these
 * Pattern flags, which desktop Java accepts. StreamSave 2.1.0–2.1.3 used one in TtsText, so
 * every voice-over line failed on phones while all tests passed. This test keeps them out.
 */
public final class AndroidCompatibilityTest {
    private static final String[] REJECTED_ON_ANDROID = {"UNICODE_CHARACTER_CLASS", "CANON_EQ"};

    private static Path mainSources() {
        Path path = Paths.get("src/main/java");
        return Files.isDirectory(path) ? path : Paths.get("app/src/main/java");
    }

    @Test public void noPatternFlagsThatAndroidRejects() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String flag : REJECTED_ON_ANDROID) {
                    if (source.contains(flag)) problems.add(file.getFileName() + " uses Pattern." + flag);
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    /** The whitespace class must be exactly Python's str.isspace(), which the SDK relies on. */
    @Test public void whitespaceIsExactlyPythons() {
        Pattern ws = Pattern.compile(TtsText.WS);
        int[] python = {0x9, 0xA, 0xB, 0xC, 0xD, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680, 0x2000, 0x2001,
                0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
                0x3000};
        java.util.Set<Integer> expected = new java.util.HashSet<>();
        for (int c : python) expected.add(c);
        for (int c = 0; c <= 0xFFFF; c++) {
            String one = String.valueOf((char) c);
            assertEquals("U+" + Integer.toHexString(c), expected.contains(c), ws.matcher(one).matches());
            assertEquals("isSpace U+" + Integer.toHexString(c), expected.contains(c), TtsText.isSpace((char) c));
        }
        assertEquals("a b", TtsText.strip(" 　a b \u001c"));
    }
}
