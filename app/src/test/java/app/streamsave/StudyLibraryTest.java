package app.streamsave;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** The online study library list, video matching, and every pack published in study-packs/. */
public final class StudyLibraryTest {
    private static final String INDEX = "{\"schemaVersion\":1,\"packs\":["
            + "{\"id\":\"aortic-dissection\",\"title\":\"Aortic dissection\",\"youtubeId\":\"Ft2bn8LcDDY\",\"version\":2,"
            + " \"fileNames\":[\"Tip 1 Aort Diseksiyonu – Acil Kalp Ameliyatı.mkv\"]},"
            + "{\"id\":\"drive-lecture\",\"file\":\"drive-lecture.zip\",\"fileNames\":[\"Lecture 3 (final).mp4\"]}]}";

    @Test public void matchesByYoutubeIdThenByFileName() throws IOException {
        StudyLibrary library = StudyLibrary.parse(INDEX);
        assertEquals(2, library.entries.size());
        StudyLibrary.Entry entry = library.match("Ft2bn8LcDDY", "anything");
        assertEquals("aortic-dissection", entry.id);
        assertEquals(StudyLibrary.BASE_URL + "aortic-dissection.json", entry.url());
        assertEquals("aortic-dissection@2", entry.stamp());
        // Same file under another extension, case and punctuation.
        assertEquals("aortic-dissection", library.match("", "TIP 1 AORT DISEKSIYONU - ACIL KALP AMELIYATI.mp4").id);
        assertEquals("drive-lecture", library.match(null, "lecture 3 final.MP4").id);
        assertNull(library.match("aaaaaaaaaaa", "Something else.mp4"));
        assertNull("Very short names never match", library.match("", "a.mp4"));
        assertEquals("drive-lecture", library.byId("drive-lecture").id);
    }

    /** A video StreamSave saved ("<title>_<job id>.mkv") and then opened as a file still finds its pack. */
    @Test public void matchesFilesStreamSaveSaved() throws IOException {
        StudyLibrary library = StudyLibrary.parse(INDEX);
        Job job = new Job();
        job.id = "3f2a9c1b-7d4e-4f00-8a11-0123456789ab";
        job.title = "Tip 1 Aort Diseksiyonu | Acil Kalp Ameliyatı";
        String saved = Publisher.filename(job, new File("video.mkv"));
        assertEquals("Tip 1 Aort Diseksiyonu _ Acil Kalp Ameliyatı_3f2a9c1b.mkv", saved);
        assertEquals("3f2a9c1b", StudyLibrary.savedJobPrefix(saved));
        assertEquals("Tip 1 Aort Diseksiyonu _ Acil Kalp Ameliyatı.mkv", StudyLibrary.withoutSavedSuffix(saved));
        assertEquals("aortic-dissection", library.match("", saved).id);
        assertEquals("aortic-dissection", library.match(null, "Tip 1 Aort Diseksiyonu - Acil Kalp Ameliyati_0a1b2c3d").id);
        // Other names are left alone.
        assertEquals("", StudyLibrary.savedJobPrefix("Lecture 3 (final).mp4"));
        assertEquals("", StudyLibrary.savedJobPrefix("scan_ABCDEF12.mp4"));
        assertEquals("", StudyLibrary.savedJobPrefix(null));
        assertEquals("Lecture 3 (final).mp4", StudyLibrary.withoutSavedSuffix("Lecture 3 (final).mp4"));
        assertNull(library.match("", "Something else_3f2a9c1b.mkv"));
    }

    @Test public void readsYoutubeIdsFromLinks() {
        assertEquals("Ft2bn8LcDDY", StudyLibrary.youtubeId("https://www.youtube.com/watch?v=Ft2bn8LcDDY"));
        assertEquals("Ft2bn8LcDDY", StudyLibrary.youtubeId("https://youtu.be/Ft2bn8LcDDY?si=x"));
        assertEquals("", StudyLibrary.youtubeId("https://drive.google.com/file/d/abc/view"));
        assertEquals("", StudyLibrary.youtubeId(null));
    }

    @Test public void rejectsInvalidLibraries() {
        String[] bad = {
                "[]",
                "{\"schemaVersion\":2,\"packs\":[]}",
                "{\"packs\":[{\"id\":\"Bad Id\"}]}",
                "{\"packs\":[{\"id\":\"a\"},{\"id\":\"a\"}]}",
                "{\"packs\":[{\"id\":\"a\",\"file\":\"../a.json\"}]}",
                "{\"packs\":[{\"id\":\"a\",\"youtubeId\":\"short\"}]}",
                "{\"packs\":[{\"id\":\"a\",\"version\":0}]}",
        };
        for (String json : bad) {
            try {
                StudyLibrary.parse(json);
                fail("Accepted: " + json);
            } catch (IOException expected) { }
        }
    }

    /** CI gate: everything published for phones to download must open in the app. */
    @Test public void everyPublishedPackIsValidAndListed() throws IOException {
        File folder = new File("../study-packs");
        if (!folder.isDirectory()) folder = new File("study-packs");
        assertTrue("study-packs folder is missing", folder.isDirectory());
        File index = new File(folder, StudyLibrary.INDEX);
        StudyLibrary library = StudyLibrary.parse(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
        Set<String> listed = new HashSet<>();
        Set<String> youtubeIds = new HashSet<>();
        for (StudyLibrary.Entry entry : library.entries) {
            File file = new File(folder, entry.file);
            assertTrue(entry.id + ": missing " + entry.file, file.isFile());
            try {
                StudyPack pack = StudyPack.read(Files.readAllBytes(file.toPath()));
                assertFalse(entry.id + " has no content", pack.overlays.isEmpty() && pack.questions.isEmpty()
                        && pack.captions.isEmpty() && pack.narration.isEmpty());
            } catch (IOException invalid) {
                fail(entry.file + " is not a valid study pack: " + invalid.getMessage());
            }
            listed.add(entry.file);
            if (!entry.youtubeId.isEmpty()) assertTrue("Two packs claim YouTube id " + entry.youtubeId, youtubeIds.add(entry.youtubeId));
        }
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.equals(StudyLibrary.INDEX) || name.equals("README.md")) continue;
            assertTrue(name + " is in study-packs/ but not listed in index.json", listed.contains(name));
        }
    }
}
