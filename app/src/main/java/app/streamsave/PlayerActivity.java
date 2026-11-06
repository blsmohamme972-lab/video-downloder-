package app.streamsave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Study player: plays a downloaded or local video, draws its study pack's
 * animated drawings and captions in sync, pauses for the multiple-choice
 * question about each part just watched, and keeps the user's own notes.
 */
@android.annotation.SuppressLint("SetTextI18n") // English-only UI, like the rest of the app
public final class PlayerActivity extends Activity {
    static final String EXTRA_URI = "video_uri";
    static final String EXTRA_KEY = "video_key";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_YOUTUBE = "youtube_url";
    private static final int MAX_INDEX_BYTES = 1024 * 1024;
    private static final int PICK_VIDEO = 301;
    private static final int PICK_PACK = 302;
    private static final int MAX_PACK_BYTES = 40 * 1024 * 1024;
    private static final long NOTE_SHOW_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private StudyStore store;
    private SharedPreferences prefs;

    private FrameLayout stage;
    private VideoView video;
    private OverlayView overlay;
    private TextView caption, noteBubble, title, status, clock, score, emptyHint;
    private SeekBar seek;
    private Button play, notesButton, drawingsToggle, questionsToggle, captionsToggle;
    private LinearLayout extras;
    private FrameLayout quizLayer;

    private Uri videoUri;
    private String key;
    private String youtubeId = "";
    private String videoName = "";
    private StudyPack pack;
    private QuizPlan plan;
    private List<StudyStore.Note> notes = new ArrayList<>();
    private boolean prepared, dragging, quizShowing;
    private boolean drawingsOn, questionsOn, captionsOn;
    private long lastPosition;
    private long lastSaved;
    /** The decoded picture size; drawings are laid over exactly this area. */
    private final int[] videoSize = new int[2];

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, video != null && video.isPlaying() ? 33 : 200);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Ui.BG);
        store = new StudyStore(new java.io.File(getFilesDir(), "study"));
        prefs = getSharedPreferences("study_player", MODE_PRIVATE);
        drawingsOn = prefs.getBoolean("drawings", true);
        questionsOn = prefs.getBoolean("questions", true);
        captionsOn = prefs.getBoolean("captions", true);
        build();
        String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri != null && !uri.isEmpty()) {
            String key = getIntent().getStringExtra(EXTRA_KEY);
            youtubeId = StudyLibrary.youtubeId(getIntent().getStringExtra(EXTRA_YOUTUBE));
            open(Uri.parse(uri), key == null || key.isEmpty() ? uri : key, getIntent().getStringExtra(EXTRA_TITLE));
        } else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri shared = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (shared != null) open(shared, shared.toString(), null);
        } else if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null) {
            open(getIntent().getData(), getIntent().getData().toString(), null);
        }
        handler.post(tick);
    }

    // ---------- layout ----------

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        LinearLayout column = Ui.vertical(this);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        overlay = new OverlayView(this);
        overlay.setDrawingsOn(drawingsOn);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        caption = Ui.text(this, "", 15, Color.WHITE, true);
        caption.setGravity(Gravity.CENTER);
        caption.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        caption.setBackground(Ui.shape(this, 0xB0000000, 8, Color.TRANSPARENT));
        caption.setPadding(dp(10), dp(4), dp(10), dp(4));
        caption.setVisibility(View.GONE);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        captionParams.setMargins(dp(12), 0, dp(12), dp(10));
        stage.addView(caption, captionParams);
        noteBubble = Ui.text(this, "", 13, Ui.TEXT, false);
        noteBubble.setBackground(Ui.shape(this, 0xE62E240C, 10, Ui.AMBER));
        noteBubble.setPadding(dp(10), dp(6), dp(10), dp(6));
        noteBubble.setMaxLines(4);
        noteBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams noteParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        noteParams.setMargins(dp(10), dp(10), dp(10), 0);
        stage.addView(noteBubble, noteParams);
        emptyHint = Ui.button(this, "Choose a video", true);
        emptyHint.setOnClickListener(v -> pickVideo());
        stage.addView(emptyHint, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        stage.setOnClickListener(v -> togglePlay());
        column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(dp(16), dp(10), dp(16), dp(14));
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        title = Ui.text(this, "Study player", 18, Ui.TEXT, true);
        title.setMaxLines(2);
        panel.addView(title, Ui.matchWrap());
        status = Ui.text(this, "Open a video, then add its study pack.", 12.5f, Ui.MUTED, false);
        panel.addView(status, Ui.matchWrap());
        panel.addView(Ui.spacer(this, 8));

        seek = new SeekBar(this);
        seek.setMax(1);
        seek.setContentDescription("Video position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) clock.setText(time(value) + " / " + time(bar.getMax()));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                seekTo(bar.getProgress());
            }
        });
        panel.addView(seek, Ui.matchWrap());
        LinearLayout timeRow = Ui.horizontal(this);
        clock = Ui.text(this, "00:00 / 00:00", 12, Ui.MUTED, false);
        timeRow.addView(clock, Ui.weight());
        score = Ui.chip(this, "No questions yet", Ui.MINT, Ui.MINT_DARK);
        timeRow.addView(score);
        panel.addView(timeRow, Ui.matchWrap());
        panel.addView(Ui.spacer(this, 10));

        LinearLayout controls = Ui.horizontal(this);
        Button back = Ui.button(this, "−10s", false);
        back.setContentDescription("Back 10 seconds");
        back.setOnClickListener(v -> seekTo(video.getCurrentPosition() - 10000));
        controls.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        play = Ui.button(this, "Play", true);
        Ui.buttonIcon(play, "play", Ui.INK);
        play.setOnClickListener(v -> togglePlay());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(48), 1.4f);
        playParams.setMarginStart(dp(8));
        playParams.setMarginEnd(dp(8));
        controls.addView(play, playParams);
        Button forward = Ui.button(this, "+10s", false);
        forward.setContentDescription("Forward 10 seconds");
        forward.setOnClickListener(v -> seekTo(video.getCurrentPosition() + 10000));
        controls.addView(forward, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(controls, Ui.matchWrap());

        extras = Ui.vertical(this);
        extras.addView(Ui.spacer(this, 10));
        LinearLayout actions = Ui.horizontal(this);
        Button packButton = Ui.button(this, "Update pack", true);
        Ui.buttonIcon(packButton, "refresh", Ui.INK);
        packButton.setContentDescription("Update this video's study pack from the study library");
        packButton.setOnClickListener(v -> {
            if (videoUri == null) pickVideo(); else checkLibrary(true);
        });
        actions.addView(packButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button addNote = Ui.button(this, "Add note", false);
        Ui.buttonIcon(addNote, "plus", Ui.TEXT);
        addNote.setOnClickListener(v -> addNote());
        LinearLayout.LayoutParams noteButtonParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        noteButtonParams.setMarginStart(dp(8));
        actions.addView(addNote, noteButtonParams);
        notesButton = Ui.button(this, "Notes", false);
        notesButton.setOnClickListener(v -> showNotes());
        LinearLayout.LayoutParams notesParams = new LinearLayout.LayoutParams(0, dp(48), 0.9f);
        notesParams.setMarginStart(dp(8));
        actions.addView(notesButton, notesParams);
        extras.addView(actions, Ui.matchWrap());
        extras.addView(Ui.spacer(this, 8));

        LinearLayout toggles = Ui.horizontal(this);
        drawingsToggle = toggleButton();
        drawingsToggle.setOnClickListener(v -> { drawingsOn = !drawingsOn; overlay.setDrawingsOn(drawingsOn); saveToggles(); });
        toggles.addView(drawingsToggle, new LinearLayout.LayoutParams(0, dp(42), 1));
        questionsToggle = toggleButton();
        questionsToggle.setOnClickListener(v -> { questionsOn = !questionsOn; saveToggles(); });
        LinearLayout.LayoutParams qParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        qParams.setMarginStart(dp(6));
        toggles.addView(questionsToggle, qParams);
        captionsToggle = toggleButton();
        captionsToggle.setOnClickListener(v -> { captionsOn = !captionsOn; saveToggles(); });
        LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        cParams.setMarginStart(dp(6));
        toggles.addView(captionsToggle, cParams);
        extras.addView(toggles, Ui.matchWrap());
        extras.addView(Ui.spacer(this, 10));
        LinearLayout lower = Ui.horizontal(this);
        Button other = Ui.button(this, "Open another video", false);
        other.setOnClickListener(v -> pickVideo());
        lower.addView(other, new LinearLayout.LayoutParams(0, dp(48), 1.6f));
        Button more = Ui.button(this, "More", false);
        Ui.buttonIcon(more, "more", Ui.TEXT);
        more.setOnClickListener(v -> packMenu());
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        moreParams.setMarginStart(dp(8));
        lower.addView(more, moreParams);
        extras.addView(lower, Ui.matchWrap());
        TextView notice = Ui.text(this, "For learning only. AI-made drawings and questions can be wrong; "
                + "check important points with a qualified teacher.", 11, Ui.QUIET, false);
        LinearLayout.LayoutParams noticeParams = Ui.matchWrap();
        noticeParams.topMargin = dp(10);
        extras.addView(notice, noticeParams);
        panel.addView(extras, Ui.matchWrap());

        quizLayer = new FrameLayout(this);
        quizLayer.setBackgroundColor(0xE6081217);
        quizLayer.setVisibility(View.GONE);
        quizLayer.setClickable(true);
        root.addView(quizLayer, new FrameLayout.LayoutParams(-1, -1));

        renderToggles();
        applyOrientation(getResources().getConfiguration());
    }

    private Button toggleButton() {
        Button button = Ui.button(this, "", false);
        button.setTextSize(12f);
        button.setPadding(dp(6), dp(6), dp(6), dp(6));
        return button;
    }

    private void renderToggles() {
        styleToggle(drawingsToggle, "Drawings", drawingsOn);
        styleToggle(questionsToggle, "Questions", questionsOn);
        styleToggle(captionsToggle, "Captions", captionsOn);
    }

    private void styleToggle(Button button, String name, boolean on) {
        button.setText(name + (on ? " ✓" : " off"));
        button.setTextColor(on ? Ui.INK : Ui.MUTED);
        button.setBackground(Ui.ripple(this, on ? Ui.MINT : Ui.SURFACE_HIGH, 12, on ? Color.TRANSPARENT : Ui.BORDER));
        button.setContentDescription(name + (on ? " shown" : " hidden"));
    }

    private void saveToggles() {
        prefs.edit().putBoolean("drawings", drawingsOn).putBoolean("questions", questionsOn)
                .putBoolean("captions", captionsOn).apply();
        renderToggles();
        update();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        applyOrientation(config);
        stage.post(this::fitOverlay);
    }

    private void applyOrientation(Configuration config) {
        // Landscape gives the video the screen; study tools stay one tap away in portrait.
        extras.setVisibility(config.orientation == Configuration.ORIENTATION_LANDSCAPE ? View.GONE : View.VISIBLE);
    }

    // ---------- video ----------

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, PICK_VIDEO);
    }

    private void open(Uri uri, String source, String name) {
        persistSnapshot();
        videoUri = uri;
        key = StudyStore.keyFor(source);
        prepared = false;
        pack = null;
        plan = null;
        notes = new ArrayList<>();
        overlay.setPack(null);
        hideQuiz();
        emptyHint.setVisibility(View.GONE);
        videoName = name != null && !name.isEmpty() ? name : displayName(uri);
        title.setText(videoName);
        status.setText("Loading video…");
        video.setOnPreparedListener(player -> {
            prepared = true;
            videoSize[0] = player.getVideoWidth();
            videoSize[1] = player.getVideoHeight();
            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoSize[0] = w;
                videoSize[1] = h;
                fitOverlay();
            });
            fitOverlay();
            seek.setMax(Math.max(1, player.getDuration()));
            long resume = prefs.getLong("pos:" + key, 0);
            if (resume > 5000 && resume < player.getDuration() - 5000) {
                video.seekTo((int) resume);
                Toast.makeText(this, "Continuing from " + time(resume), Toast.LENGTH_SHORT).show();
            }
            lastPosition = Math.max(0, video.getCurrentPosition());
            renderStatus();
        });
        video.setOnErrorListener((player, what, extra) -> {
            message("Video", "Android's built-in player cannot play this file's format. "
                    + "Try a different quality (MP4 plays most widely) or open it with Open in the library.");
            return true;
        });
        video.setOnCompletionListener(player -> renderPlay());
        video.setVideoURI(uri);
        loadStudy();
    }

    private void fitOverlay() {
        if (!prepared) return;
        int vw = videoSize[0], vh = videoSize[1];
        int pw = stage.getWidth(), ph = stage.getHeight();
        if (vw < 1 || vh < 1 || pw < 1 || ph < 1) {
            stage.postDelayed(this::fitOverlay, 120);
            return;
        }
        // VideoView letterboxes the picture; drawings use the same centred rectangle.
        float scale = Math.min((float) pw / vw, (float) ph / vh);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, Math.round(vw * scale)), Math.max(1, Math.round(vh * scale)), Gravity.CENTER);
        overlay.setLayoutParams(params);
    }

    private void togglePlay() {
        if (videoUri == null) { pickVideo(); return; }
        if (quizShowing) return;
        if (video.isPlaying()) video.pause(); else video.start();
        renderPlay();
    }

    private void seekTo(long ms) {
        if (!prepared) return;
        int target = (int) Math.max(0, Math.min(ms, Math.max(0, video.getDuration() - 500)));
        video.seekTo(target);
        lastPosition = target; // a seek never triggers a question
        overlay.setTimeMs(target);
        update();
    }

    private void update() {
        if (video == null || !prepared) return;
        long position = Math.max(0, video.getCurrentPosition());
        int duration = Math.max(1, video.getDuration());
        if (!dragging) {
            seek.setMax(duration);
            seek.setProgress((int) position);
            clock.setText(time(position) + " / " + time(duration));
        }
        overlay.setTimeMs(position);
        String spoken = captionsOn && pack != null ? pack.captionAt(position) : "";
        caption.setText(spoken);
        caption.setVisibility(spoken.isEmpty() || quizShowing ? View.GONE : View.VISIBLE);
        StudyStore.Note current = null;
        for (StudyStore.Note note : notes) {
            if (note.timeMs <= position && position - note.timeMs < NOTE_SHOW_MS) current = note;
        }
        noteBubble.setText(current == null ? "" : "Your note · " + current.text);
        noteBubble.setVisibility(current == null || quizShowing ? View.GONE : View.VISIBLE);
        if (video.isPlaying() && questionsOn && plan != null && !quizShowing) {
            StudyPack.Question due = plan.due(lastPosition, position);
            if (due != null) showQuestion(due);
        }
        lastPosition = position;
        if (video.isPlaying() && position - lastSaved > 5000) {
            lastSaved = position;
            prefs.edit().putLong("pos:" + key, position).apply();
        }
        renderPlay();
    }

    private void renderPlay() {
        boolean playing = video.isPlaying();
        play.setText(playing ? "Pause" : "Play");
        Ui.buttonIcon(play, playing ? "pause" : "play", Ui.INK);
    }

    private void persistSnapshot() {
        if (key != null && prepared) prefs.edit().putLong("pos:" + key, Math.max(0, video.getCurrentPosition())).apply();
    }

    // ---------- study pack ----------

    private void loadStudy() {
        final String loading = key;
        io.execute(() -> {
            StudyPack loaded = null;
            List<StudyStore.Note> savedNotes = Collections.emptyList();
            Map<String, Integer> answers = null;
            String problem = null;
            try {
                byte[] raw = store.loadPack(loading);
                if (raw != null) loaded = StudyPack.read(raw);
                savedNotes = store.notes(loading);
                answers = store.answers(loading);
            } catch (IOException error) {
                problem = error.getMessage();
            }
            final StudyPack result = loaded;
            final List<StudyStore.Note> resultNotes = savedNotes;
            final Map<String, Integer> resultAnswers = answers;
            final String failure = problem;
            handler.post(() -> {
                if (isFinishing() || !loading.equals(key)) return;
                notes = new ArrayList<>(resultNotes);
                applyPack(result, resultAnswers);
                if (failure != null) message("Study data", failure);
                checkLibrary(false); // picks up a newly published pack without any tap
            });
        });
    }

    private void applyPack(StudyPack loaded, Map<String, Integer> answers) {
        pack = loaded;
        plan = loaded == null ? null : new QuizPlan(loaded.questions, answers);
        overlay.setPack(loaded);
        renderStatus();
        update();
    }

    private void renderStatus() {
        notesButton.setText(notes.isEmpty() ? "Notes" : "Notes (" + notes.size() + ")");
        if (videoUri == null) return;
        if (pack == null) {
            status.setText("No study pack yet. Send this video's Google Drive link to your AI assistant, then tap Update pack.");
        } else {
            status.setText(pack.title + " · " + pack.overlays.size() + " drawings · "
                    + pack.questions.size() + " questions" + (pack.captions.isEmpty() ? "" : " · captions"));
        }
        if (plan == null || plan.total() == 0) {
            score.setText(pack == null ? "No questions yet" : "No questions");
        } else {
            score.setText(plan.answered() + "/" + plan.total() + " answered · " + plan.correct() + " correct");
        }
    }

    private void packMenu() {
        if (videoUri == null) { pickVideo(); return; }
        if (pack == null) {
            String[] items = {"Choose from the study library", "Add a study pack file…"};
            new AlertDialog.Builder(this).setTitle("Study pack").setItems(items, (dialog, which) -> {
                if (which == 0) chooseFromLibrary();
                else pickPack();
            }).show();
            return;
        }
        String[] items = {"Choose a different pack from the library", "Add a study pack file…",
                "Retake all questions", "Remove study pack", "About this pack"};
        new AlertDialog.Builder(this).setTitle("Study pack").setItems(items, (dialog, which) -> {
            if (which == 0) chooseFromLibrary();
            else if (which == 1) pickPack();
            else if (which == 2) retakeQuiz();
            else if (which == 3) confirmRemovePack();
            else message(pack.title, pack.overlays.size() + " drawings\n" + pack.questions.size()
                    + " questions\n" + pack.captions.size() + " captions"
                    + (pack.language.isEmpty() ? "" : "\nLanguage: " + pack.language));
        }).show();
    }

    // ---------- study library (packs published online; no files for the user) ----------

    /** Finds this video's pack in the study library and installs it when it is new or newer. */
    private void checkLibrary(boolean manual) {
        if (videoUri == null || key == null) return;
        final String target = key;
        final String youtube = youtubeId;
        final String name = videoName;
        final String installed = prefs.getString("lib:" + target, "");
        final boolean hasPack = pack != null;
        // A pack the user added from a file, or removed on purpose, is only replaced on request.
        if (!manual && (installed.equals("file") || installed.equals("removed"))) return;
        if (manual) status.setText("Checking the study library…");
        io.execute(() -> {
            StudyLibrary library;
            try {
                library = StudyLibrary.parse(new String(fetch(StudyLibrary.BASE_URL + StudyLibrary.INDEX
                        + "?t=" + System.currentTimeMillis() / 60000, MAX_INDEX_BYTES), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException error) {
                if (manual) handler.post(() -> {
                    renderStatus();
                    message("Study library", "Could not reach the study library. Check the internet connection and try again.\n\n"
                            + error.getMessage());
                });
                return;
            }
            StudyLibrary.Entry entry = library.match(youtube, name);
            if (entry == null && installed.contains("@")) entry = library.byId(installed.substring(0, installed.indexOf('@')));
            if (entry == null) {
                if (manual) handler.post(() -> {
                    renderStatus();
                    if (library.entries.isEmpty()) {
                        message("Study library", "No study packs have been published yet. Send this video's "
                                + "Google Drive link to your AI assistant and ask for a study pack, then tap Update pack.");
                    } else {
                        offerLibrary(library, "No pack is linked to this video yet. Choose its pack:");
                    }
                });
                return;
            }
            if (entry.stamp().equals(installed) && hasPack) {
                if (manual) handler.post(() -> {
                    renderStatus();
                    Toast.makeText(this, "This study pack is up to date.", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            installFromLibrary(entry, target, manual);
        });
    }

    private void chooseFromLibrary() {
        final String target = key;
        status.setText("Opening the study library…");
        io.execute(() -> {
            try {
                StudyLibrary library = StudyLibrary.parse(new String(fetch(StudyLibrary.BASE_URL + StudyLibrary.INDEX
                        + "?t=" + System.currentTimeMillis() / 60000, MAX_INDEX_BYTES), java.nio.charset.StandardCharsets.UTF_8));
                handler.post(() -> {
                    if (!target.equals(key)) return;
                    renderStatus();
                    if (library.entries.isEmpty()) message("Study library", "No study packs have been published yet.");
                    else offerLibrary(library, "Choose the pack for this video:");
                });
            } catch (IOException error) {
                handler.post(() -> {
                    renderStatus();
                    message("Study library", "Could not reach the study library.\n\n" + error.getMessage());
                });
            }
        });
    }

    private void offerLibrary(StudyLibrary library, String heading) {
        String[] titles = new String[library.entries.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = library.entries.get(i).title;
        final String target = key;
        new AlertDialog.Builder(this).setTitle(heading).setItems(titles, (dialog, which) ->
                io.execute(() -> installFromLibrary(library.entries.get(which), target, true))).show();
    }

    /** Runs on the io thread. Keeps quiz answers: question ids stay stable across pack versions. */
    private void installFromLibrary(StudyLibrary.Entry entry, String target, boolean manual) {
        try {
            byte[] raw = fetch(entry.url() + "?v=" + entry.version, MAX_PACK_BYTES);
            StudyPack loaded = StudyPack.read(raw); // validate before replacing anything
            store.savePack(target, raw);
            Map<String, Integer> answers = store.answers(target);
            prefs.edit().putString("lib:" + target, entry.stamp()).apply();
            handler.post(() -> {
                if (isFinishing() || !target.equals(key)) return;
                applyPack(loaded, answers);
                Toast.makeText(this, "Study pack ready: " + loaded.questions.size() + " questions, "
                        + loaded.overlays.size() + " drawings.", Toast.LENGTH_LONG).show();
            });
        } catch (IOException error) {
            if (manual) handler.post(() -> {
                renderStatus();
                message("Study pack not updated", error.getMessage());
            });
        }
    }

    /** HTTPS download from the study library only, with a size limit. */
    private static byte[] fetch(String address, int maxBytes) throws IOException {
        java.net.URL url = new java.net.URL(address);
        if (!"https".equals(url.getProtocol()) || !"raw.githubusercontent.com".equals(url.getHost())) {
            throw new IOException("Study packs are only downloaded from the StreamSave study library.");
        }
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "StreamSave-StudyPlayer");
        try {
            int status = connection.getResponseCode();
            if (status == 404) throw new IOException("The study library has no file at " + url.getPath() + ".");
            if (status != 200) throw new IOException("The study library answered HTTP " + status + ".");
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) throw new IOException("The study pack is too large.");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (out.size() + n > maxBytes) throw new IOException("The study pack is too large.");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void pickPack() {
        // The system picker lists Google Drive alongside the phone's own files.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/zip",
                "application/x-zip-compressed", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, PICK_PACK);
    }

    private void importPack(Uri uri) {
        final String target = key;
        status.setText("Reading study pack…");
        io.execute(() -> {
            String error = null;
            StudyPack loaded = null;
            Map<String, Integer> answers = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open the selected file.");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (out.size() + n > MAX_PACK_BYTES) throw new IOException("The study pack is larger than 40 MB.");
                    out.write(buffer, 0, n);
                }
                byte[] raw = out.toByteArray();
                loaded = StudyPack.read(raw); // validate before replacing anything
                store.savePack(target, raw);
                store.saveAnswers(target, Collections.emptyMap());
                prefs.edit().putString("lib:" + target, "file").apply();
                answers = Collections.emptyMap();
            } catch (IOException | SecurityException failure) {
                error = failure.getMessage() == null ? "The study pack could not be read." : failure.getMessage();
            }
            final StudyPack result = loaded;
            final Map<String, Integer> resultAnswers = answers;
            final String problem = error;
            handler.post(() -> {
                if (isFinishing() || !target.equals(key)) return;
                if (problem != null) {
                    renderStatus();
                    message("Study pack not added", problem);
                    return;
                }
                applyPack(result, resultAnswers);
                message("Study pack added", result.overlays.size() + " drawings, " + result.questions.size()
                        + " questions and " + result.captions.size() + " captions are ready. "
                        + "Press Play; the video pauses for a question after each part.");
            });
        });
    }

    private void retakeQuiz() {
        if (plan == null) return;
        plan.reset();
        saveAnswers();
        renderStatus();
        Toast.makeText(this, "All questions will be asked again.", Toast.LENGTH_SHORT).show();
    }

    private void confirmRemovePack() {
        new AlertDialog.Builder(this).setTitle("Remove study pack?")
                .setMessage("The drawings, questions and your answers for this video are removed. Your own notes stay.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    final String target = key;
                    prefs.edit().putString("lib:" + target, "removed").apply();
                    io.execute(() -> {
                        try { store.deletePack(target); } catch (IOException ignored) { }
                        handler.post(() -> { if (target.equals(key)) applyPack(null, null); });
                    });
                }).show();
    }

    private void saveAnswers() {
        if (plan == null) return;
        final String target = key;
        final Map<String, Integer> answers = plan.answers();
        io.execute(() -> {
            try { store.saveAnswers(target, answers); } catch (IOException ignored) { }
        });
    }

    // ---------- questions ----------

    private void showQuestion(StudyPack.Question question) {
        video.pause();
        renderPlay();
        quizShowing = true;
        caption.setVisibility(View.GONE);
        noteBubble.setVisibility(View.GONE);
        quizLayer.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout holder = Ui.vertical(this);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(dp(18), dp(24), dp(18), dp(24));
        scroll.addView(holder, new ScrollView.LayoutParams(-1, -2));
        quizLayer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = Ui.vertical(this);
        card.setBackground(Ui.shape(this, Ui.SURFACE, 20, Ui.BORDER));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        holder.addView(card, new LinearLayout.LayoutParams(Math.min(dp(560), getResources().getDisplayMetrics().widthPixels - dp(36)), -2));

        int number = plan.questions().indexOf(question) + 1;
        card.addView(Ui.eyebrow(this, "QUESTION " + number + " OF " + plan.total()
                + "  ·  " + time(question.startMs) + "–" + time(question.endMs)), Ui.matchWrap());
        card.addView(Ui.spacer(this, 8));
        TextView text = Ui.text(this, question.question, 17, Ui.TEXT, true);
        card.addView(text, Ui.matchWrap());
        card.addView(Ui.spacer(this, 14));

        List<Button> choices = new ArrayList<>();
        LinearLayout after = Ui.vertical(this);
        after.setVisibility(View.GONE);
        for (int i = 0; i < question.options.size(); i++) {
            final int choice = i;
            Button option = Ui.button(this, (char) ('A' + i) + ".  " + question.options.get(i), false);
            option.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            option.setOnClickListener(v -> answer(question, choice, choices, after));
            choices.add(option);
            LinearLayout.LayoutParams params = Ui.matchWrap();
            params.bottomMargin = dp(8);
            card.addView(option, params);
        }
        card.addView(after, Ui.matchWrap());

        Button skip = Ui.button(this, "Skip for now", false);
        skip.setTextColor(Ui.MUTED);
        skip.setOnClickListener(v -> {
            plan.skip(question);
            hideQuiz();
            video.start();
            renderPlay();
        });
        LinearLayout.LayoutParams skipParams = Ui.matchWrap();
        skipParams.topMargin = dp(4);
        card.addView(skip, skipParams);
        skip.setTag("skip");
        quizLayer.setVisibility(View.VISIBLE);
        text.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    private void answer(StudyPack.Question question, int choice, List<Button> choices, LinearLayout after) {
        boolean correct = plan.answer(question, choice);
        saveAnswers();
        renderStatus();
        for (int i = 0; i < choices.size(); i++) {
            Button button = choices.get(i);
            button.setEnabled(false);
            if (i == question.answer) {
                button.setBackground(Ui.shape(this, Ui.MINT_DARK, 14, Ui.MINT));
                button.setTextColor(Ui.MINT);
            } else if (i == choice) {
                button.setBackground(Ui.shape(this, 0xFF3A1C1E, 14, Ui.RED));
                button.setTextColor(Ui.RED);
            } else {
                button.setAlpha(0.55f);
            }
        }
        View card = (View) after.getParent();
        View skip = card.findViewWithTag("skip");
        if (skip != null) skip.setVisibility(View.GONE);
        after.removeAllViews();
        TextView verdict = Ui.text(this, correct ? "Correct ✓" : "Not quite — the answer is "
                + (char) ('A' + question.answer) + ".", 15, correct ? Ui.MINT : Ui.RED, true);
        after.addView(verdict, Ui.matchWrap());
        if (!question.explanation.isEmpty()) {
            TextView why = Ui.text(this, question.explanation, 14, Ui.MUTED, false);
            LinearLayout.LayoutParams whyParams = Ui.matchWrap();
            whyParams.topMargin = dp(6);
            after.addView(why, whyParams);
        }
        LinearLayout row = Ui.horizontal(this);
        Button replay = Ui.button(this, "Watch this part again", false);
        replay.setOnClickListener(v -> {
            hideQuiz();
            seekTo(question.startMs);
            video.start();
            renderPlay();
        });
        row.addView(replay, new LinearLayout.LayoutParams(0, -2, 1));
        Button next = Ui.button(this, "Continue", true);
        next.setOnClickListener(v -> {
            hideQuiz();
            video.start();
            renderPlay();
        });
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
        nextParams.setMarginStart(dp(8));
        row.addView(next, nextParams);
        LinearLayout.LayoutParams rowParams = Ui.matchWrap();
        rowParams.topMargin = dp(14);
        after.addView(row, rowParams);
        after.setVisibility(View.VISIBLE);
    }

    private void hideQuiz() {
        quizShowing = false;
        quizLayer.setVisibility(View.GONE);
        quizLayer.removeAllViews();
        lastPosition = video == null ? 0 : Math.max(0, video.getCurrentPosition());
    }

    @Override public void onBackPressed() {
        if (quizShowing) {
            hideQuiz();
            return;
        }
        super.onBackPressed();
    }

    // ---------- notes ----------

    private void addNote() {
        if (videoUri == null || !prepared) { pickVideo(); return; }
        video.pause();
        renderPlay();
        final long at = Math.max(0, video.getCurrentPosition());
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setHint("What do you want to remember here?");
        new AlertDialog.Builder(this).setTitle("Note at " + time(at)).setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    final String text = input.getText().toString();
                    final String target = key;
                    io.execute(() -> {
                        String problem = null;
                        List<StudyStore.Note> updated = null;
                        try {
                            store.addNote(target, at, text, System.currentTimeMillis());
                            updated = store.notes(target);
                        } catch (IOException error) {
                            problem = error.getMessage();
                        }
                        final String failure = problem;
                        final List<StudyStore.Note> result = updated;
                        handler.post(() -> {
                            if (failure != null) { message("Note not saved", failure); return; }
                            if (!target.equals(key)) return;
                            notes = new ArrayList<>(result);
                            renderStatus();
                            Toast.makeText(this, "Note saved at " + time(at), Toast.LENGTH_SHORT).show();
                        });
                    });
                }).show();
    }

    private void showNotes() {
        if (notes.isEmpty()) {
            message("Your notes", "No notes yet. Pause at any moment and tap Add note.");
            return;
        }
        String[] items = new String[notes.size()];
        for (int i = 0; i < notes.size(); i++) items[i] = time(notes.get(i).timeMs) + "  —  " + notes.get(i).text;
        new AlertDialog.Builder(this).setTitle("Your notes").setItems(items, (dialog, which) -> {
            StudyStore.Note note = notes.get(which);
            new AlertDialog.Builder(this).setTitle(time(note.timeMs)).setMessage(note.text)
                    .setPositiveButton("Go to this moment", (d, w) -> seekTo(note.timeMs))
                    .setNeutralButton("Delete", (d, w) -> deleteNote(note))
                    .setNegativeButton("Close", null).show();
        }).show();
    }

    private void deleteNote(StudyStore.Note note) {
        final String target = key;
        io.execute(() -> {
            List<StudyStore.Note> updated = null;
            try {
                store.deleteNote(target, note.id);
                updated = store.notes(target);
            } catch (IOException ignored) { }
            final List<StudyStore.Note> result = updated;
            handler.post(() -> {
                if (result == null || !target.equals(key)) return;
                notes = new ArrayList<>(result);
                renderStatus();
            });
        });
    }

    // ---------- results & lifecycle ----------

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_VIDEO) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some providers grant only temporary access; the video still opens now.
            }
            open(uri, uri.toString(), null);
        } else if (request == PICK_PACK) {
            if (videoUri == null) { message("Study pack", "Open the video first, then add its study pack."); return; }
            importPack(uri);
        }
    }

    @Override protected void onPause() {
        if (video != null && video.isPlaying()) video.pause();
        persistSnapshot();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (video != null) video.stopPlayback();
        io.shutdown();
        super.onDestroy();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) { }
        return "Video";
    }

    private void message(String heading, String text) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(heading).setMessage(text).setPositiveButton("OK", null).show();
    }

    static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(float value) { return Ui.dp(this, value); }
}
