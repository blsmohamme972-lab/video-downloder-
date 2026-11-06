package app.streamsave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * When to speak each voice-over line and how long the video may run while it
 * speaks. Like questions, a line starts only when playback (not a seek)
 * passes its start. Plain Java so it is unit-tested on the JVM.
 */
final class NarrationPlan {
    private final List<StudyPack.Narration> lines;

    NarrationPlan(List<StudyPack.Narration> lines) {
        List<StudyPack.Narration> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        this.lines = Collections.unmodifiableList(sorted);
    }

    List<StudyPack.Narration> lines() { return lines; }

    /** The line to start when playback moved from previousMs to currentMs, or null. */
    StudyPack.Narration due(long previousMs, long currentMs) {
        if (currentMs <= previousMs || currentMs - previousMs > QuizPlan.MAX_PLAYBACK_STEP_MS) return null;
        StudyPack.Narration found = null;
        for (StudyPack.Narration line : lines) {
            if (line.startMs > currentMs) break;
            if (line.startMs > previousMs) found = line; // the latest one passed in this step
        }
        return found;
    }

    /** Video time by which this line should have finished: its endMs, else the next line's start. */
    long windowEnd(StudyPack.Narration line) {
        if (line.endMs > 0) return line.endMs;
        int index = lines.indexOf(line);
        return index >= 0 && index + 1 < lines.size() ? lines.get(index + 1).startMs : Long.MAX_VALUE;
    }

    /** Lines to prepare first: the ones about to be spoken, then the rest in order. */
    List<StudyPack.Narration> preparationOrder(long positionMs) {
        List<StudyPack.Narration> ahead = new ArrayList<>();
        List<StudyPack.Narration> behind = new ArrayList<>();
        for (StudyPack.Narration line : lines) (line.startMs + 2000 >= positionMs ? ahead : behind).add(line);
        ahead.addAll(behind);
        return ahead;
    }
}
