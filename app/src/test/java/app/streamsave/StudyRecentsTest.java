package app.streamsave;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** The Library's "Study videos": newest first, one entry per video, tolerant of bad data. */
public final class StudyRecentsTest {
    private static StudyRecents.Item item(String uri, String title, long at) {
        return new StudyRecents.Item(uri, uri, title, "", at);
    }

    @Test public void newestFirstAndOneEntryPerVideo() {
        String json = StudyRecents.add("", item("content://a", "A.mp4", 1));
        json = StudyRecents.add(json, item("content://b", "B.mp4", 2));
        json = StudyRecents.add(json, item("content://a", "A.mp4", 3)); // reopened
        List<StudyRecents.Item> items = StudyRecents.parse(json);
        assertEquals(2, items.size());
        assertEquals("content://a", items.get(0).uri);
        assertEquals(3, items.get(0).openedAt);
        assertEquals("content://b", items.get(1).uri);

        items = StudyRecents.parse(StudyRecents.remove(json, "content://a"));
        assertEquals(1, items.size());
        assertEquals("content://b", items.get(0).uri);
    }

    @Test public void keepsAtMostMaxAndKeepsAllFields() {
        String json = "";
        for (int i = 0; i < StudyRecents.MAX + 5; i++) json = StudyRecents.add(json, item("content://" + i, i + ".mp4", i));
        List<StudyRecents.Item> items = StudyRecents.parse(json);
        assertEquals(StudyRecents.MAX, items.size());
        assertEquals("content://" + (StudyRecents.MAX + 4), items.get(0).uri);

        StudyRecents.Item full = new StudyRecents.Item("content://x", "job:1", "X.mkv", "Ft2bn8LcDDY", 9);
        StudyRecents.Item read = StudyRecents.parse(StudyRecents.add("", full)).get(0);
        assertEquals("job:1", read.source);
        assertEquals("X.mkv", read.title);
        assertEquals("Ft2bn8LcDDY", read.youtubeId);
    }

    @Test public void brokenOrForeignDataReadsAsEmpty() {
        assertTrue(StudyRecents.parse(null).isEmpty());
        assertTrue(StudyRecents.parse("not json").isEmpty());
        assertTrue(StudyRecents.parse("{\"uri\":\"x\"}").isEmpty());
        assertEquals(1, StudyRecents.parse("[{\"uri\":\"content://a\",\"future\":true},{\"title\":\"no uri\"}]").size());
    }

    @Test public void showsReadableTitles() {
        assertEquals("Tip 1 Aort Diseksiyonu – Acil Kalp Ameliyatı _ Type 1 Aortic Dissection Surgery (Surgeon’s View)",
                StudyRecents.displayTitle("Tip 1 Aort Diseksiyonu – Acil Kalp Ameliyatı _ Type 1 Aortic Dissection "
                        + "Surgery (Surgeon’s View)_26a2accb.mkv"));
        assertEquals("Lecture 3 (final)", StudyRecents.displayTitle("Lecture 3 (final).mp4"));
        assertEquals("Video", StudyRecents.displayTitle(""));
    }
}
