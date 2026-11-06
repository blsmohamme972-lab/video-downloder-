package app.streamsave;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Study pack parsing, question timing and per-video storage; no device needed. */
public final class StudyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String PACK = "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"title\": \"Aortic dissection repair\",\n"
            + "  \"language\": \"en\",\n"
            + "  \"durationMs\": 180000,\n"
            + "  \"overlays\": [\n"
            + "    {\"type\":\"arrow\",\"startMs\":5000,\"endMs\":15000,\"x\":0.2,\"y\":0.2,\"x2\":0.6,\"y2\":0.6,\n"
            + "     \"text\":\"Dissected aorta\",\"keyframes\":[{\"timeMs\":5000,\"x\":0.2,\"y\":0.2,\"x2\":0.6,\"y2\":0.6},\n"
            + "                                {\"timeMs\":15000,\"x\":0.4,\"y\":0.2,\"x2\":0.8,\"y2\":0.6}]},\n"
            + "    {\"type\":\"spotlight\",\"startMs\":1000,\"endMs\":4000,\"x\":0.5,\"y\":0.5,\"x2\":0.7,\"y2\":0.7},\n"
            + "    {\"type\":\"label\",\"startMs\":2000,\"endMs\":3000,\"text\":\"Cautery\",\"animation\":\"fade\"}\n"
            + "  ],\n"
            + "  \"questions\": [\n"
            + "    {\"id\":\"m2\",\"startMs\":60000,\"endMs\":120000,\"question\":\"Second minute?\",\n"
            + "     \"options\":[\"A\",\"B\",\"C\",\"D\"],\"answer\":2,\"explanation\":\"Because C.\"},\n"
            + "    {\"id\":\"m1\",\"startMs\":0,\"endMs\":60000,\"question\":\"First minute?\",\n"
            + "     \"options\":[\"Yes\",\"No\"],\"answer\":0}\n"
            + "  ],\n"
            + "  \"captions\": [{\"startMs\":0,\"endMs\":2500,\"text\":\"Hello\"},{\"startMs\":2000,\"endMs\":4000,\"text\":\"World\"}]\n"
            + "}";

    @Test public void parsesDrawingsQuestionsAndCaptions() throws IOException {
        StudyPack pack = StudyPack.read(PACK.getBytes(StandardCharsets.UTF_8));
        assertEquals("Aortic dissection repair", pack.title);
        assertEquals(3, pack.overlays.size());
        assertEquals("spotlight", pack.overlays.get(0).type);      // sorted by start time
        assertEquals("draw", pack.overlays.get(2).animation);       // arrow default
        assertEquals("fade", pack.overlays.get(1).animation);       // explicit
        assertEquals(0.3f, pack.overlays.get(2).at(10000).x, 0.0001f); // halfway between keyframes
        assertEquals("m1", pack.questions.get(0).id);              // sorted by when they are asked
        assertEquals(60000, pack.questions.get(0).askAtMs);         // defaults to the end of its minute
        assertEquals("Hello\nWorld", pack.captionAt(2200));
        assertEquals("", pack.captionAt(5000));
    }

    @Test public void readsZipPacksWithImagesAndVersionOneCues() throws IOException {
        String v1 = "{\"schemaVersion\":1,\"title\":\"Old\",\"cues\":[{\"type\":\"image\",\"startMs\":0,\"endMs\":1000,"
                + "\"asset\":\"images/a.png\"},{\"type\":\"note\",\"startMs\":0,\"endMs\":1000,\"text\":\"n\"}]}";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(v1.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("images/a.png"));
            zip.write(new byte[]{1, 2, 3});
        }
        StudyPack pack = StudyPack.read(bytes.toByteArray());
        assertEquals(2, pack.overlays.size());
        assertArrayEquals(new byte[]{1, 2, 3}, pack.images.get("images/a.png"));
    }

    @Test public void rejectsUnsafeOrInvalidPacks() {
        String[] bad = {
                "not json",
                "{\"schemaVersion\":3,\"questions\":[]}",
                "{\"overlays\":[]}",
                "{\"overlays\":[{\"type\":\"laser\",\"startMs\":0,\"endMs\":5}]}",
                "{\"overlays\":[{\"type\":\"ring\",\"startMs\":10,\"endMs\":5}]}",
                "{\"overlays\":[{\"type\":\"ring\",\"startMs\":0,\"endMs\":5,\"x\":1.5}]}",
                "{\"overlays\":[{\"type\":\"label\",\"startMs\":0,\"endMs\":5}]}",
                "{\"overlays\":[{\"type\":\"image\",\"startMs\":0,\"endMs\":5,\"asset\":\"images/x.png\"}]}",
                "{\"questions\":[{\"startMs\":0,\"endMs\":5,\"question\":\"q\",\"options\":[\"only\"],\"answer\":0}]}",
                "{\"questions\":[{\"startMs\":0,\"endMs\":5,\"question\":\"q\",\"options\":[\"a\",\"b\"],\"answer\":2}]}",
                "{\"questions\":[{\"id\":\"x\",\"startMs\":0,\"endMs\":5,\"question\":\"q\",\"options\":[\"a\",\"b\"],\"answer\":0},"
                        + "{\"id\":\"x\",\"startMs\":0,\"endMs\":5,\"question\":\"q\",\"options\":[\"a\",\"b\"],\"answer\":0}]}",
        };
        for (String json : bad) {
            try {
                StudyPack.read(json.getBytes(StandardCharsets.UTF_8));
                fail("Accepted: " + json);
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void rejectsZipPathTraversal() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../evil.json"));
            zip.write(PACK.getBytes(StandardCharsets.UTF_8));
        }
        try {
            StudyPack.read(bytes.toByteArray());
            fail("Accepted a ZIP path outside the pack");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unsafe"));
        }
    }

    @Test public void asksEachQuestionOnceWhenPlaybackPassesIt() throws IOException {
        StudyPack pack = StudyPack.read(PACK.getBytes(StandardCharsets.UTF_8));
        QuizPlan plan = new QuizPlan(pack.questions, null);
        assertNull(plan.due(58000, 59000));
        StudyPack.Question first = plan.due(59500, 60100);
        assertEquals("m1", first.id);
        assertNull("A seek across a question does not ask it", plan.due(60100, 121000));
        assertTrue(plan.answer(first, 0));
        assertNull("Answered questions are not asked again", plan.due(59500, 60100));
        StudyPack.Question second = plan.due(119800, 120200);
        assertFalse(plan.answer(second, 1));
        assertEquals(2, plan.answered());
        assertEquals(1, plan.correct());
        plan.reset();
        assertEquals(0, plan.answered());
        assertEquals("m1", plan.due(59500, 60100).id);
    }

    @Test public void skippedQuestionsReturnNextTimeAndSavedAnswersAreKept() throws IOException {
        StudyPack pack = StudyPack.read(PACK.getBytes(StandardCharsets.UTF_8));
        Map<String, Integer> saved = new HashMap<>();
        saved.put("m2", 2);
        saved.put("removed", 1);
        QuizPlan plan = new QuizPlan(pack.questions, saved);
        assertEquals(1, plan.answered());                 // unknown ids are ignored
        assertNull(plan.due(119800, 120200));               // m2 already answered
        StudyPack.Question first = plan.due(59500, 60100);
        plan.skip(first);
        assertNull(plan.due(59500, 60100));
        assertEquals("m1", new QuizPlan(pack.questions, plan.answers()).due(59500, 60100).id);
    }

    @Test public void storesNotesPacksAndAnswersPerVideo() throws IOException {
        StudyStore store = new StudyStore(folder.getRoot());
        String a = StudyStore.keyFor("job:one");
        String b = StudyStore.keyFor("content://video/2");
        assertNotEquals(a, b);
        assertEquals(a, StudyStore.keyFor("job:one"));

        store.addNote(a, 90000, "  Second note  ", 2);
        StudyStore.Note first = store.addNote(a, 30000, "First", 1);
        List<StudyStore.Note> notes = store.notes(a);
        assertEquals(2, notes.size());
        assertEquals("First", notes.get(0).text);           // sorted by video time
        assertEquals("Second note", notes.get(1).text);     // trimmed
        assertTrue(store.notes(b).isEmpty());
        store.deleteNote(a, first.id);
        assertEquals(1, store.notes(a).size());
        try {
            store.addNote(a, 0, "   ", 3);
            fail("Saved an empty note");
        } catch (IOException expected) { }

        assertNull(store.loadPack(a));
        store.savePack(a, PACK.getBytes(StandardCharsets.UTF_8));
        assertEquals(PACK, new String(store.loadPack(a), StandardCharsets.UTF_8));

        Map<String, Integer> answers = new HashMap<>();
        answers.put("m1", 0);
        store.saveAnswers(a, answers);
        assertEquals(Integer.valueOf(0), store.answers(a).get("m1"));
        store.deletePack(a);
        assertNull(store.loadPack(a));
        assertTrue("Removing a pack clears its answers", store.answers(a).isEmpty());
        assertEquals("Notes survive removing the pack", 1, store.notes(a).size());
    }
}
