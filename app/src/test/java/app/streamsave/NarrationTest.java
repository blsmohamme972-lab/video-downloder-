package app.streamsave;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Voice-over lines in study packs and when they are spoken. */
public final class NarrationTest {
    private static final String PACK = "{\"schemaVersion\":2,\"title\":\"Voice\",\"language\":\"en\",\"narration\":["
            + "{\"id\":\"b\",\"startMs\":20000,\"text\":\"Second line.\",\"voice\":\"m2\",\"speed\":1.1},"
            + "{\"id\":\"a\",\"startMs\":5000,\"endMs\":12000,\"text\":\"First line.\"},"
            + "{\"id\":\"c\",\"startMs\":30000,\"text\":\"Last line.\"}]}";

    private static StudyPack pack(String json) throws IOException {
        return StudyPack.read(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void readsVoiceOverLinesInTimeOrder() throws IOException {
        StudyPack pack = pack(PACK);
        assertEquals(3, pack.narration.size());
        StudyPack.Narration first = pack.narration.get(0);
        assertEquals("a", first.id);
        assertEquals(12000, first.endMs);
        assertEquals("", first.voice);          // player setting is used
        assertEquals(0f, first.speed, 0f);
        StudyPack.Narration second = pack.narration.get(1);
        assertEquals("M2", second.voice);       // normalized
        assertEquals(1.1f, second.speed, 0.0001f);
        assertEquals(-1, second.endMs);
    }

    @Test public void voiceOverAloneIsAValidPackAndBadLinesAreRejected() throws IOException {
        assertEquals(1, pack("{\"narration\":[{\"startMs\":0,\"text\":\"Hi.\"}]}").narration.size());
        String[] bad = {
                "{\"narration\":[{\"startMs\":0}]}",
                "{\"narration\":[{\"startMs\":10,\"endMs\":5,\"text\":\"x\"}]}",
                "{\"narration\":[{\"startMs\":0,\"text\":\"x\",\"voice\":\"Z9\"}]}",
                "{\"narration\":[{\"startMs\":0,\"text\":\"x\",\"speed\":3}]}",
                "{\"narration\":[{\"id\":\"x\",\"startMs\":0,\"text\":\"x\"},{\"id\":\"x\",\"startMs\":1,\"text\":\"y\"}]}",
        };
        for (String json : bad) {
            try {
                pack(json);
                fail("Accepted: " + json);
            } catch (IOException expected) { }
        }
    }

    @Test public void linesStartOnPlaybackAndWaitForTheirWindow() throws IOException {
        NarrationPlan plan = new NarrationPlan(pack(PACK).narration);
        assertNull(plan.due(4000, 4900));
        assertEquals("a", plan.due(4900, 5100).id);
        assertNull("a seek past a line does not start it", plan.due(5100, 21000));
        assertEquals("b", plan.due(19900, 20050).id);
        assertEquals(12000, plan.windowEnd(plan.lines().get(0)));          // its own endMs
        assertEquals(30000, plan.windowEnd(plan.lines().get(1)));          // next line's start
        assertEquals(Long.MAX_VALUE, plan.windowEnd(plan.lines().get(2))); // last line
        assertEquals("b", plan.preparationOrder(19000).get(0).id);         // upcoming first
        assertEquals("a", plan.preparationOrder(19000).get(2).id);         // earlier ones last
    }
}
