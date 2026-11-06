package app.streamsave;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device smoke tests: native view/lifecycle behavior only; never starts a download. */
@SuppressWarnings("deprecation")
public final class UiSmokeTest extends InstrumentationTestCase {
    private MainActivity activity;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Intent launch = new Intent(getInstrumentation().getTargetContext(), MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (MainActivity) getInstrumentation().startActivitySync(launch);
        getInstrumentation().waitForIdleSync();
    }

    @Override protected void tearDown() throws Exception {
        if (activity != null && !activity.isDestroyed()) onUi(() -> activity.finish());
        getInstrumentation().waitForIdleSync();
        super.tearDown();
    }

    public void testCleanStartupAndBlankInput() throws Exception {
        screenshot("ui-home.png");
        onUi(() -> {
            assertEquals(View.VISIBLE, view("home-scroll", View.class).getVisibility());
            assertEquals(View.GONE, view("video-card", View.class).getVisibility());
            EditText input = view("video-link", EditText.class);
            assertTrue(input.isEnabled());
            input.setText("");
            input.onEditorAction(EditorInfo.IME_ACTION_GO);
            assertTrue(view("analysis-message", TextView.class).getText().toString().contains("Paste"));
            assertEquals(View.GONE, view("video-card", View.class).getVisibility());
        });
    }

    public void testInvalidLinksShowLocalValidation() throws Exception {
        int jobCount = ((App) activity.getApplication()).jobs().size();
        String[] invalid = {"not a link", "https://example.com/video.mp4", "javascript:alert(1)",
                "https://www.youtube.com/playlist?list=PL_invalid"};
        for (String value : invalid) {
            onUi(() -> {
                EditText input = view("video-link", EditText.class);
                input.setText(value);
                input.onEditorAction(EditorInfo.IME_ACTION_GO);
                String message = view("analysis-message", TextView.class).getText().toString();
                assertTrue("Expected a YouTube link validation message for " + value,
                        message.contains("YouTube"));
                assertEquals(View.GONE, view("video-card", View.class).getVisibility());
            });
        }
        assertEquals("Invalid input must not add a download", jobCount, ((App) activity.getApplication()).jobs().size());
    }

    public void testQualityRowsUseSourceModelAndKeepSelection() throws Exception {
        Quality high = new Quality("1080", "1080p", "60 fps · MKV", "299+140", false, 1080, 123456);
        Quality small = new Quality("720", "720p", "30 fps · MKV", "136+140", false, 720, 45678);
        Quality audio = new Quality("audio", "Audio only", "Original audio · M4A", "140", true, 0, 12345);
        VideoDetails details = new VideoDetails("BaW_jenozKc", "Instrumentation fixture", "Local test", "00:10",
                "", "https://www.youtube.com/watch?v=BaW_jenozKc", 10, Arrays.asList(high, small, audio));
        onUi(() -> {
            Object session = field(activity, "session");
            put(session, "details", details);
            put(session, "selectedQualityId", null);
            call(activity, "renderAnalysis", new Class<?>[]{boolean.class}, true);
            LinearLayout list = view("quality-list", LinearLayout.class);
            assertEquals("Only the supplied formats should be rendered", 3, list.getChildCount());
            assertTrue(view("quality-1080", View.class).isSelected());
            view("quality-audio", View.class).performClick();
            assertTrue(view("quality-audio", View.class).isSelected());
            assertFalse(view("quality-1080", View.class).isSelected());
            assertTrue(view("download-video", Button.class).getText().toString().contains("Audio only"));
            call(activity, "renderAppState", new Class<?>[0]);
            assertTrue("A progress refresh must preserve the selected quality", view("quality-audio", View.class).isSelected());
        });
        awaitRenderedFrame();
        awaitAutomaticQualityReveal();
        onUi(() -> view("home-scroll", ScrollView.class).scrollTo(0,
                Math.max(0, view("video-card", View.class).getTop() - Ui.dp(activity, 16))));
        screenshot("ui-quality.png");
    }

    public void testShareIntentPopulatesInputWithoutNetworkLookup() throws Exception {
        String shared = "A shared video https://youtu.be/BaW_jenozKc";
        onUi(() -> view("tab-library", View.class).performClick());
        Intent intent = new Intent(activity, MainActivity.class).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, shared)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startAndAwaitIntentDelivery(intent);
        onUi(() -> {
            assertEquals(shared, view("video-link", EditText.class).getText().toString());
            assertEquals(View.VISIBLE, view("home-scroll", View.class).getVisibility());
            assertEquals(View.GONE, view("video-card", View.class).getVisibility());
            assertFalse("Sharing should wait for the user's Find video action", (Boolean) field(field(activity, "session"), "analyzing"));
        });
    }

    public void testRecreationRetainsInputAndSelectedTab() throws Exception {
        String input = "https://youtu.be/BaW_jenozKc";
        AtomicReference<Object> sessionRef = new AtomicReference<>();
        MainActivity original = activity;
        onUi(() -> {
            view("video-link", EditText.class).setText(input);
            view("tab-library", View.class).performClick();
            sessionRef.set(field(activity, "session"));
            activity.recreate();
        });
        long deadline = SystemClock.uptimeMillis() + 8000;
        MainActivity recreated = null;
        while (SystemClock.uptimeMillis() < deadline) {
            getInstrumentation().waitForIdleSync();
            @SuppressWarnings("unchecked")
            WeakReference<MainActivity> owner = (WeakReference<MainActivity>) field(sessionRef.get(), "owner");
            MainActivity next = owner.get();
            if (next != null && next != original && !next.isDestroyed()) { recreated = next; break; }
            SystemClock.sleep(40);
        }
        assertNotNull("The activity should be recreated and attach to the retained session", recreated);
        activity = recreated;
        onUi(() -> {
            assertEquals(input, view("video-link", EditText.class).getText().toString());
            assertEquals(View.VISIBLE, view("library-scroll", View.class).getVisibility());
            assertTrue(view("tab-library", View.class).isSelected());
        });
    }

    public void testNotificationIntentOpensLibraryAndKeepsDraft() throws Exception {
        String input = "https://youtu.be/BaW_jenozKc";
        onUi(() -> {
            view("tab-download", View.class).performClick();
            assertEquals("Notification test must start outside Library", View.GONE,
                    view("library-scroll", View.class).getVisibility());
            view("video-link", EditText.class).setText(input);
        });
        Intent intent = new Intent(activity, MainActivity.class).putExtra("show_library", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startAndAwaitIntentDelivery(intent);
        onUi(() -> {
            assertEquals(View.VISIBLE, view("library-scroll", View.class).getVisibility());
            assertEquals("Notification navigation must preserve the current draft", input,
                    view("video-link", EditText.class).getText().toString());
            assertFalse("The navigation request should be consumed before recreation",
                    activity.getIntent().getBooleanExtra("show_library", false));
        });
    }

    /** Main-looper idleness alone does not wait for ActivityManager to deliver a new intent. */
    private void startAndAwaitIntentDelivery(Intent intent) throws Exception {
        final String marker = "app.streamsave.test.INTENT_DELIVERY";
        String token = Long.toString(SystemClock.elapsedRealtimeNanos());
        intent.putExtra(marker, token);
        MainActivity expected = activity;
        Application application = expected.getApplication();
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<MainActivity> resumed = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks observer = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity current) {
                // Android resumes the activity after onNewIntent completes. The
                // token distinguishes this delivery from an unrelated resume.
                if (current instanceof MainActivity && current.getIntent() != null
                        && token.equals(current.getIntent().getStringExtra(marker))) {
                    resumed.set((MainActivity) current);
                    delivered.countDown();
                }
            }
            @Override public void onActivityCreated(Activity current, Bundle state) { }
            @Override public void onActivityStarted(Activity current) { }
            @Override public void onActivityPaused(Activity current) { }
            @Override public void onActivityStopped(Activity current) { }
            @Override public void onActivitySaveInstanceState(Activity current, Bundle state) { }
            @Override public void onActivityDestroyed(Activity current) { }
        };
        try {
            onUi(() -> {
                application.registerActivityLifecycleCallbacks(observer);
                expected.startActivity(intent);
            });
            assertTrue("The activity did not resume after receiving the launched intent",
                    delivered.await(15, TimeUnit.SECONDS));
            onUi(() -> {
                activity = resumed.get();
                assertSame("SINGLE_TOP must deliver to the existing activity", expected, activity);
                assertEquals("The resumed activity must hold this exact intent", token,
                        activity.getIntent().getStringExtra(marker));
                activity.getIntent().removeExtra(marker);
            });
        } finally {
            onUi(() -> application.unregisterActivityLifecycleCallbacks(observer));
        }
    }

    private <T extends View> T view(String tag, Class<T> type) {
        View result = activity.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull("Missing view: " + tag, result);
        return type.cast(result);
    }

    private interface UiWork { void run() throws Exception; }

    private void screenshot(String name) throws Exception {
        awaitRenderedFrame();
        Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        File folder = getInstrumentation().getTargetContext().getExternalFilesDir("qa");
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) { bitmap.recycle(); return; }
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }

    /** Exercises the production layout observer before the test aligns its screenshot. */
    private void awaitAutomaticQualityReveal() throws Exception {
        CountDownLatch revealed = new CountDownLatch(1);
        AtomicReference<ScrollView> scrolling = new AtomicReference<>();
        onUi(() -> {
            ScrollView scroll = view("home-scroll", ScrollView.class);
            scrolling.set(scroll);
            Runnable check = () -> {
                View card = view("video-card", View.class);
                int range = Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
                int expected = Math.min(range, Math.max(0, card.getTop() - Ui.dp(activity, 16)));
                if (scroll.getScrollY() > 0 && Math.abs(scroll.getScrollY() - expected) <= Ui.dp(activity, 2)) {
                    revealed.countDown();
                }
            };
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> check.run());
            check.run();
        });
        boolean complete = revealed.await(15, TimeUnit.SECONDS);
        onUi(() -> {
            scrolling.get().setOnScrollChangeListener(null);
            assertTrue("Loading qualities must automatically reveal the measured card", scrolling.get().getScrollY() > 0);
            assertTrue("The automatic quality reveal did not settle at the card", complete);
        });
    }

    /** Waits for real rendering, so captures do not race layout or scroll invalidations. */
    private void awaitRenderedFrame() throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<View> root = new AtomicReference<>();
        AtomicReference<Runnable> commit = new AtomicReference<>();
        AtomicReference<ViewTreeObserver.OnDrawListener> draw = new AtomicReference<>();
        onUi(() -> {
            View decor = activity.getWindow().getDecorView();
            root.set(decor);
            if (Build.VERSION.SDK_INT >= 29 && decor.isHardwareAccelerated()) {
                Runnable callback = frame::countDown;
                commit.set(callback);
                decor.getViewTreeObserver().registerFrameCommitCallback(callback);
            } else {
                ViewTreeObserver.OnDrawListener callback = new ViewTreeObserver.OnDrawListener() {
                    @Override public void onDraw() {
                        decor.post(() -> {
                            if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(this);
                            frame.countDown();
                        });
                    }
                };
                draw.set(callback);
                decor.getViewTreeObserver().addOnDrawListener(callback);
            }
            decor.invalidate();
        });
        boolean rendered = frame.await(15, TimeUnit.SECONDS);
        onUi(() -> {
            ViewTreeObserver observer = root.get().getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (Build.VERSION.SDK_INT >= 29 && commit.get() != null) observer.unregisterFrameCommitCallback(commit.get());
            if (draw.get() != null) observer.removeOnDrawListener(draw.get());
        });
        assertTrue("The screen did not render a frame for its screenshot", rendered);
        getInstrumentation().waitForIdleSync();
    }

    private void onUi(UiWork work) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            try { work.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        if (error instanceof AssertionError) throw (AssertionError) error;
        if (error instanceof Exception) throw (Exception) error;
        if (error != null) throw new AssertionError(error);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
}
