package app.streamsave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides when to pause playback for a question. A question is asked once
 * playback, not a seek, carries the video past its askAtMs. Answers are kept
 * (and saved) so a question is not asked again until the quiz is reset.
 *
 * Plain Java so the timing rules are unit-tested on the JVM.
 */
final class QuizPlan {
    /** Larger position jumps than this between two checks are seeks, not playback. */
    static final long MAX_PLAYBACK_STEP_MS = 1500;

    private final List<StudyPack.Question> questions;
    private final Map<String, Integer> answers = new LinkedHashMap<>();
    private final Set<String> skipped = new HashSet<>();

    QuizPlan(List<StudyPack.Question> questions, Map<String, Integer> savedAnswers) {
        List<StudyPack.Question> sorted = new ArrayList<>(questions);
        sorted.sort((a, b) -> Long.compare(a.askAtMs, b.askAtMs));
        this.questions = Collections.unmodifiableList(sorted);
        Set<String> known = new HashSet<>();
        for (StudyPack.Question question : sorted) known.add(question.id);
        if (savedAnswers != null) {
            for (Map.Entry<String, Integer> entry : savedAnswers.entrySet()) {
                // Ignore answers for questions that are no longer in the pack.
                if (known.contains(entry.getKey()) && entry.getValue() != null) answers.put(entry.getKey(), entry.getValue());
            }
        }
    }

    List<StudyPack.Question> questions() { return questions; }

    /** The question to ask when playback moved from previousMs to currentMs, or null. */
    StudyPack.Question due(long previousMs, long currentMs) {
        if (currentMs <= previousMs || currentMs - previousMs > MAX_PLAYBACK_STEP_MS) return null;
        for (StudyPack.Question question : questions) {
            if (question.askAtMs > currentMs) break;
            if (question.askAtMs > previousMs && !answers.containsKey(question.id) && !skipped.contains(question.id)) {
                return question;
            }
        }
        return null;
    }

    /** Records an answer and returns whether it was correct. */
    boolean answer(StudyPack.Question question, int choice) {
        if (choice < 0 || choice >= question.options.size()) throw new IllegalArgumentException("No such option");
        answers.put(question.id, choice);
        return choice == question.answer;
    }

    /** Skips a question for this viewing only; it is asked again next time. */
    void skip(StudyPack.Question question) { skipped.add(question.id); }

    Integer answerFor(StudyPack.Question question) { return answers.get(question.id); }

    int total() { return questions.size(); }

    int answered() { return answers.size(); }

    int correct() {
        int count = 0;
        for (StudyPack.Question question : questions) {
            Integer choice = answers.get(question.id);
            if (choice != null && choice == question.answer) count++;
        }
        return count;
    }

    /** Clears all answers so every question is asked again. */
    void reset() {
        answers.clear();
        skipped.clear();
    }

    Map<String, Integer> answers() { return Collections.unmodifiableMap(new LinkedHashMap<>(answers)); }
}
