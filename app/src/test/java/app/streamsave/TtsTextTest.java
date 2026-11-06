package app.streamsave;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The Java text preparation must match Supertone's official Python SDK
 * exactly; tts_text_cases.json holds outputs recorded from supertonic 1.3.1.
 */
public final class TtsTextTest {
    private static JSONArray cases() throws Exception {
        try (InputStream in = TtsTextTest.class.getClassLoader().getResourceAsStream("tts_text_cases.json")) {
            assertNotNull("tts_text_cases.json is missing", in);
            return new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> list(JSONArray array) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        return out;
    }

    @Test public void preprocessingMatchesTheOfficialSdk() throws Exception {
        JSONArray cases = cases();
        assertTrue(cases.length() >= 10);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            assertEquals("case " + i, c.getString("preprocessed"), TtsText.preprocess(c.getString("text"), "en"));
        }
    }

    @Test public void chunkingMatchesTheOfficialSdk() throws Exception {
        JSONArray cases = cases();
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            assertEquals("case " + i + " (300)", list(c.getJSONArray("chunks300")), TtsText.chunk(c.getString("text"), 300));
            assertEquals("case " + i + " (60)", list(c.getJSONArray("chunks60")), TtsText.chunk(c.getString("text"), 60));
        }
    }

    @Test public void idsSkipCharactersTheModelCannotSay() {
        int[] indexer = new int[65536];
        java.util.Arrays.fill(indexer, -1);
        indexer['<'] = 1; indexer['e'] = 2; indexer['n'] = 3; indexer['>'] = 4; indexer['a'] = 5; indexer['.'] = 6; indexer['/'] = 7;
        assertArrayEquals(new long[]{1, 2, 3, 4, 5, 6, 1, 7, 2, 3, 4}, TtsText.ids("<en>a一.</en>", indexer));
        assertEquals("一", TtsText.unsupported("a一", indexer));
    }
}
