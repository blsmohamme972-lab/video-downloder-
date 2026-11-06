package app.streamsave;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Device tests for state persistence, competing queue actions and safe output paths. */
@SuppressWarnings("deprecation")
public final class DownloadStateTest extends AndroidTestCase {
    private File testRoot;
    private Context isolated;

    @Override protected void setUp() throws Exception {
        super.setUp();
        testRoot = new File(getContext().getCacheDir(), "state-test-" + UUID.randomUUID());
        assertTrue(testRoot.mkdirs());
        isolated = new ContextWrapper(getContext()) {
            @Override public File getFilesDir() { return testRoot; }
        };
    }

    @Override protected void tearDown() throws Exception {
        App.deleteTree(testRoot);
        super.tearDown();
    }

    private Job example() {
        Job j = new Job();
        j.id = UUID.randomUUID().toString();
        j.url = "https://www.youtube.com/watch?v=BaW_jenozKc";
        j.title = "Test video"; j.format = "137+251"; j.qualityLabel = "1080p";
        j.createdAt = 1;
        return j;
    }

    public void testPauseSurvivesStoreRecreationWithExactFormat() throws Exception {
        JobStore store = new JobStore(isolated);
        Job job = example();
        store.add(job);
        store.transition(job.id, j -> j.state == State.QUEUED, j -> {
            j.state = State.DOWNLOADING; j.progress = 37.25f;
        });
        store.transition(job.id, j -> j.state == State.DOWNLOADING, j -> j.state = State.PAUSED);
        Job restored = new JobStore(isolated).get(job.id);
        assertNotNull(restored);
        assertEquals(State.PAUSED, restored.state);
        assertEquals(37.25f, restored.progress, .001f);
        assertEquals("137+251", restored.format);
        assertEquals(job.url, restored.url);
    }

    public void testPauseCannotBeOverwrittenByLateProgressOrQueueClaim() throws Exception {
        JobStore store = new JobStore(isolated);
        Job job = example(); store.add(job);
        assertTrue(store.transition(job.id, j -> j.state == State.QUEUED, j -> j.state = State.PAUSED));
        assertFalse(store.transition(job.id, j -> j.state == State.QUEUED, j -> j.state = State.DOWNLOADING));
        assertFalse(store.transition(job.id, j -> j.state == State.DOWNLOADING, j -> j.progress = 70));
        assertNull(store.next());
        assertEquals(State.PAUSED, store.get(job.id).state);
    }

    public void testReturnedJobsCannotMutatePersistedState() throws Exception {
        JobStore store = new JobStore(isolated);
        Job job = example(); store.add(job);
        store.get(job.id).state = State.COMPLETED;
        store.list().get(0).format = "18";
        assertEquals(State.QUEUED, store.get(job.id).state);
        assertEquals("137+251", new JobStore(isolated).get(job.id).format);
    }

    public void testQueueRunsOldestAndSkipsPaused() throws Exception {
        JobStore store = new JobStore(isolated);
        Job first = example(), second = example();
        store.add(first); store.add(second);
        assertEquals(first.id, store.next().id);
        store.change(first.id, j -> j.state = State.PAUSED);
        assertEquals(second.id, store.next().id);
        store.remove(second.id);
        assertNull(new JobStore(isolated).next());
        assertEquals(1, store.list().size());
    }

    public void testCorruptHistoryIsPreservedWithoutRemovingMedia() throws Exception {
        File media = new File(testRoot, "keep.mkv");
        try (FileOutputStream out = new FileOutputStream(media)) { out.write(42); }
        File history = new File(testRoot, "downloads-v1.json");
        try (FileOutputStream out = new FileOutputStream(history)) { out.write("broken".getBytes(StandardCharsets.UTF_8)); }
        JobStore store = new JobStore(isolated);
        assertEquals(0, store.list().size());
        assertFalse(store.warning.isEmpty());
        assertTrue(media.exists());
        File[] backups = testRoot.listFiles((dir, name) -> name.startsWith("downloads-unreadable-"));
        assertNotNull(backups); assertEquals(1, backups.length);
    }

    public void testFilenameCannotEscapeOutputFolderAndPreservesUnicode() {
        Job job = example();
        job.title = "../عربي/very:long?title*" + "🎥".repeat(120);
        String name = Publisher.filename(job, new File("media.mkv"));
        assertFalse(name.contains("/")); assertFalse(name.contains("\\"));
        assertFalse(name.startsWith(".")); assertTrue(name.contains("عربي"));
        assertTrue(name.endsWith(".mkv"));
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length < 200);
    }

    public void testFriendlyErrorExplainsActionWithoutNativeTraceback() {
        String error = App.friendly(new Exception("ERROR: HTTP Error 403: Forbidden\n/data/user/0/app.streamsave/python"));
        assertTrue(error.contains("YouTube"));
        assertFalse(error.contains("/data/"));
        assertTrue(error.contains("update"));
    }

    public void testFinalizationCannotBeRemovedOrOverwrittenByStaleStopAction() throws Exception {
        JobStore store = new JobStore(isolated);
        Job job = example(); store.add(job);
        Job staleUiSnapshot = store.get(job.id);
        assertEquals(State.QUEUED, staleUiSnapshot.state);
        store.change(job.id, j -> j.state = State.SAVING);
        assertFalse("A stale remove tap must not remove an exporting job",
                store.removeIf(job.id, j -> j.state != State.MERGING && j.state != State.SAVING));
        store.change(job.id, j -> { j.state = State.COMPLETED; j.contentUri = "content://media/external/downloads/1"; });
        assertFalse("A stale shutdown snapshot must not turn a saved file into a paused job",
                store.transition(job.id, Job::active, j -> j.state = State.PAUSED));
        assertEquals(State.COMPLETED, new JobStore(isolated).get(job.id).state);
        assertTrue(store.removeIf(job.id, j -> j.state == State.COMPLETED));
    }
}
