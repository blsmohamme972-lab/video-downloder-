package app.streamsave;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A single native screen for inspecting video qualities and managing persistent downloads. */
public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION = 41;
    private App app;
    private Session session;
    private LinearLayout root;
    private LinearLayout homePage;
    private LinearLayout libraryPage;
    private LinearLayout engineBanner;
    private LinearLayout videoCard;
    private LinearLayout qualityList;
    private LinearLayout analysisRow;
    private LinearLayout homeEmpty;
    private LinearLayout libraryEmpty;
    private ScrollView homeScroll;
    private ScrollView libraryScroll;
    private EditText linkInput;
    private Button findButton;
    private Button pasteButton;
    private Button downloadButton;
    private Button retryEngineButton;
    private TextView downloadTab;
    private TextView libraryTab;
    private TextView engineStatus;
    private TextView engineMessage;
    private TextView analysisMessage;
    private TextView videoTitle;
    private TextView videoMeta;
    private TextView librarySummary;
    private ProgressBar engineSpinner;
    private ProgressBar analysisSpinner;
    private JobList homeJobs;
    private JobList libraryJobs;
    private int tab;
    private boolean installUpdateAfterPermission;
    private boolean internalTextChange;
    private long lastEnqueueTime;
    private String lastEnqueueKey = "";
    private ViewTreeObserver.OnGlobalLayoutListener pendingQualityReveal;
    private final Runnable appListener = this::renderAppState;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (App) getApplication();
        Object retained = getLastNonConfigurationInstance();
        session = retained instanceof Session ? (Session) retained : new Session();
        if (savedInstanceState != null) {
            session.input = savedInstanceState.getString("input", session.input);
            tab = savedInstanceState.getInt("tab", 0);
            if (session.selectedQualityId == null) session.selectedQualityId = savedInstanceState.getString("quality");
        }
        configureWindow();
        buildScreen();
        session.owner = new WeakReference<>(this);
        internalTextChange = true;
        linkInput.setText(session.input);
        linkInput.setSelection(linkInput.getText().length());
        internalTextChange = false;
        renderAnalysis(false);
        selectTab(tab);
        renderAppState();
        if (savedInstanceState == null && retained == null) readIncomingIntent(getIntent());
        else if (getIntent() != null && getIntent().getBooleanExtra("show_library", false)) readIncomingIntent(getIntent());
        if (!app.ready && !app.initializing && empty(app.initializationError)) app.initEngine();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Ui.BG);
        window.setNavigationBarColor(Ui.BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void buildScreen() {
        FrameLayout outside = new FrameLayout(this);
        outside.setBackgroundColor(Ui.BG);
        outside.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        Ui.MaxWidthFrame constrained = new Ui.MaxWidthFrame(this);
        FrameLayout.LayoutParams maxParams = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL);
        outside.addView(constrained, maxParams);
        root = Ui.vertical(this);
        root.setFocusableInTouchMode(true);
        constrained.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(outside);
        outside.requestApplyInsets();

        LinearLayout header = Ui.horizontal(this);
        header.setPadding(dp(22), dp(16), dp(16), dp(16));
        Ui.IconView logo = new Ui.IconView(this, "download", Ui.INK);
        logo.size(24);
        logo.setBackground(Ui.shape(this, Ui.MINT, 14, Color.TRANSPARENT));
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brand = Ui.vertical(this);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView brandName = Ui.text(this, "StreamSave", 22, Ui.TEXT, true);
        brandName.setMaxLines(1);
        brandName.setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, TypedValue.COMPLEX_UNIT_SP);
        brand.addView(brandName, Ui.matchWrap());
        TextView brandCaption = Ui.text(this, "YOUR VIDEOS, TO GO", 9.5f, Ui.QUIET, true);
        brandCaption.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams captionParams = Ui.matchWrap();
        captionParams.topMargin = dp(5);
        brand.addView(brandCaption, captionParams);
        header.addView(brand, Ui.weight());
        Ui.IconView settings = Ui.iconButton(this, "settings", "Settings and download engine");
        settings.setOnClickListener(view -> showSettings(view));
        header.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, Ui.matchWrap());

        LinearLayout tabs = Ui.horizontal(this);
        tabs.setPadding(dp(5), dp(5), dp(5), dp(5));
        tabs.setBackground(Ui.shape(this, Ui.SURFACE, 17, Ui.BORDER));
        downloadTab = makeTab("Download", () -> selectTab(0));
        libraryTab = makeTab("Library", () -> selectTab(1));
        downloadTab.setTag("tab-download");
        libraryTab.setTag("tab-library");
        tabs.addView(downloadTab, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(libraryTab, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams tabParams = Ui.matchWrap();
        tabParams.setMargins(dp(22), 0, dp(22), dp(6));
        root.addView(tabs, tabParams);
        Button studio = Ui.button(this, "Study player  •  Questions, drawings, notes", false);
        studio.setOnClickListener(view -> startActivity(new Intent(this, PlayerActivity.class)));
        LinearLayout.LayoutParams studioParams = Ui.matchWrap();
        studioParams.setMargins(dp(22), dp(3), dp(22), dp(8));
        root.addView(studio, studioParams);

        FrameLayout pages = new FrameLayout(this);
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        homeScroll = makeScroll();
        libraryScroll = makeScroll();
        homeScroll.setTag("home-scroll");
        libraryScroll.setTag("library-scroll");
        homePage = Ui.vertical(this);
        libraryPage = Ui.vertical(this);
        homePage.setPadding(dp(22), dp(23), dp(22), dp(30));
        libraryPage.setPadding(dp(22), dp(23), dp(22), dp(30));
        homeScroll.addView(homePage, new ScrollView.LayoutParams(-1, -2));
        libraryScroll.addView(libraryPage, new ScrollView.LayoutParams(-1, -2));
        pages.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(libraryScroll, new FrameLayout.LayoutParams(-1, -1));
        buildHome();
        buildLibrary();
    }

    private TextView makeTab(String title, Runnable action) {
        TextView view = Ui.text(this, title, 14, Ui.MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setMinimumHeight(dp(44));
        view.setPadding(dp(5), dp(9), dp(5), dp(9));
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(v -> action.run());
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
        return view;
    }

    private ScrollView makeScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private void buildHome() {
        LinearLayout headingRow = Ui.horizontal(this);
        TextView eyebrow = Ui.eyebrow(this, "A LITTLE MORE OFFLINE");
        headingRow.addView(eyebrow, Ui.weight());
        engineStatus = Ui.chip(this, "Starting", Ui.AMBER, Ui.SURFACE_HIGH);
        engineStatus.setContentDescription("Download engine status");
        engineStatus.setOnClickListener(view -> showSettings(view));
        engineStatus.setClickable(true);
        engineStatus.setFocusable(true);
        headingRow.addView(engineStatus);
        homePage.addView(headingRow, Ui.matchWrap());
        homePage.addView(Ui.spacer(this, 14));
        TextView headline = Ui.text(this, "Keep the good stuff.", 29, Ui.TEXT, true);
        headline.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heading(headline);
        homePage.addView(headline, Ui.matchWrap());
        homePage.addView(Ui.spacer(this, 9));
        homePage.addView(Ui.text(this, "Your favorite videos. Your choice of quality.", 14, Ui.MUTED, false), Ui.matchWrap());
        homePage.addView(Ui.spacer(this, 23));

        engineBanner = Ui.vertical(this);
        engineBanner.setPadding(dp(16), dp(15), dp(16), dp(15));
        engineBanner.setBackground(Ui.shape(this, Ui.SURFACE, 16, Ui.BORDER));
        LinearLayout engineLine = Ui.horizontal(this);
        engineSpinner = spinner();
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        spinnerParams.setMarginEnd(dp(12));
        engineLine.addView(engineSpinner, spinnerParams);
        engineMessage = Ui.text(this, "Preparing the download engine…", 13, Ui.MUTED, false);
        engineMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        engineLine.addView(engineMessage, Ui.weight());
        engineBanner.addView(engineLine, Ui.matchWrap());
        retryEngineButton = Ui.button(this, "Retry engine setup", false);
        retryEngineButton.setOnClickListener(view -> {
            app.initEngine();
            renderAppState();
        });
        LinearLayout.LayoutParams retryParams = Ui.matchWrap();
        retryParams.topMargin = dp(12);
        engineBanner.addView(retryEngineButton, retryParams);
        LinearLayout.LayoutParams bannerParams = Ui.matchWrap();
        bannerParams.bottomMargin = dp(16);
        homePage.addView(engineBanner, bannerParams);

        LinearLayout inputCard = card(18);
        LinearLayout labelRow = Ui.horizontal(this);
        labelRow.addView(Ui.eyebrow(this, "VIDEO LINK"), Ui.weight());
        Ui.IconView link = new Ui.IconView(this, "link", Ui.QUIET);
        link.size(17);
        labelRow.addView(link, new LinearLayout.LayoutParams(dp(20), dp(20)));
        inputCard.addView(labelRow, Ui.matchWrap());
        inputCard.addView(Ui.spacer(this, 13));

        LinearLayout inputRow = Ui.horizontal(this);
        inputRow.setBackground(Ui.shape(this, Ui.BG, 12, Ui.BORDER));
        linkInput = new EditText(this);
        linkInput.setTag("video-link");
        linkInput.setTextSize(14);
        linkInput.setTextColor(Ui.TEXT);
        linkInput.setHintTextColor(Ui.QUIET);
        linkInput.setHint("Paste a YouTube link");
        linkInput.setContentDescription("YouTube video link");
        linkInput.setBackgroundColor(Color.TRANSPARENT);
        linkInput.setPadding(dp(14), dp(15), dp(8), dp(15));
        linkInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        linkInput.setSingleLine(true);
        linkInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        linkInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkInput.setSelectAllOnFocus(false);
        linkInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        linkInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { startAnalysis(); return true; }
            return false;
        });
        inputRow.addView(linkInput, new LinearLayout.LayoutParams(0, -2, 1));
        pasteButton = Ui.button(this, "Paste", false);
        pasteButton.setTextColor(Ui.MINT);
        pasteButton.setTextSize(12);
        pasteButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        pasteButton.setMinimumHeight(dp(48));
        pasteButton.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10, Color.TRANSPARENT));
        pasteButton.setOnClickListener(view -> pasteLink());
        inputRow.addView(pasteButton, new LinearLayout.LayoutParams(-2, dp(50)));
        inputCard.addView(inputRow, Ui.matchWrap());
        inputCard.addView(Ui.spacer(this, 14));
        findButton = Ui.button(this, "Find video", true);
        findButton.setTag("find-video");
        Ui.buttonIcon(findButton, "arrow", Ui.INK);
        findButton.setOnClickListener(view -> startAnalysis());
        inputCard.addView(findButton, Ui.matchWrap());

        analysisRow = Ui.horizontal(this);
        analysisRow.setGravity(Gravity.TOP);
        analysisSpinner = spinner();
        LinearLayout.LayoutParams lookupSpinner = new LinearLayout.LayoutParams(dp(16), dp(16));
        lookupSpinner.setMarginEnd(dp(10));
        lookupSpinner.topMargin = dp(2);
        analysisRow.addView(analysisSpinner, lookupSpinner);
        analysisMessage = Ui.text(this, "", 12, Ui.MUTED, false);
        analysisMessage.setTag("analysis-message");
        analysisMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        analysisRow.addView(analysisMessage, Ui.weight());
        LinearLayout.LayoutParams analysisParams = Ui.matchWrap();
        analysisParams.topMargin = dp(13);
        inputCard.addView(analysisRow, analysisParams);
        homePage.addView(inputCard, Ui.matchWrap());

        LinearLayout features = Ui.horizontal(this);
        features.setGravity(Gravity.CENTER);
        features.setPadding(0, dp(15), 0, dp(18));
        addFeature(features, "bolt", "Original quality");
        addFeature(features, "pause", "Pause & resume");
        homePage.addView(features, Ui.matchWrap());

        videoCard = card(18);
        videoCard.setTag("video-card");
        LinearLayout metadata = Ui.horizontal(this);
        metadata.setGravity(Gravity.TOP);
        Ui.IconView videoIcon = new Ui.IconView(this, "play", Ui.MINT);
        videoIcon.size(24);
        videoIcon.setBackground(Ui.shape(this, Ui.MINT_DARK, 15, Color.TRANSPARENT));
        metadata.addView(videoIcon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titleBlock = Ui.vertical(this);
        titleBlock.setPadding(dp(13), dp(2), 0, 0);
        videoTitle = Ui.text(this, "", 16, Ui.TEXT, true);
        videoTitle.setMaxLines(3);
        videoTitle.setEllipsize(TextUtils.TruncateAt.END);
        heading(videoTitle);
        titleBlock.addView(videoTitle, Ui.matchWrap());
        videoMeta = Ui.text(this, "", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams metadataParams = Ui.matchWrap();
        metadataParams.topMargin = dp(7);
        titleBlock.addView(videoMeta, metadataParams);
        metadata.addView(titleBlock, Ui.weight());
        videoCard.addView(metadata, Ui.matchWrap());
        videoCard.addView(Ui.spacer(this, 22));
        videoCard.addView(Ui.eyebrow(this, "CHOOSE QUALITY"), Ui.matchWrap());
        videoCard.addView(Ui.spacer(this, 11));
        qualityList = Ui.vertical(this);
        qualityList.setTag("quality-list");
        videoCard.addView(qualityList, Ui.matchWrap());
        TextView formatNote = Ui.text(this, "Only formats available for this video are shown.", 11.5f, Ui.QUIET, false);
        LinearLayout.LayoutParams noteParams = Ui.matchWrap();
        noteParams.setMargins(0, dp(5), 0, dp(16));
        videoCard.addView(formatNote, noteParams);
        downloadButton = Ui.button(this, "Download", true);
        downloadButton.setTag("download-video");
        Ui.buttonIcon(downloadButton, "download", Ui.INK);
        downloadButton.setOnClickListener(view -> enqueueSelected());
        videoCard.addView(downloadButton, Ui.matchWrap());
        LinearLayout.LayoutParams videoParams = Ui.matchWrap();
        videoParams.bottomMargin = dp(25);
        homePage.addView(videoCard, videoParams);

        LinearLayout jobsHeading = Ui.horizontal(this);
        TextView recentTitle = Ui.text(this, "Your downloads", 18, Ui.TEXT, true);
        heading(recentTitle);
        jobsHeading.addView(recentTitle, Ui.weight());
        Button seeAll = textButton("View all", Ui.MINT);
        seeAll.setOnClickListener(view -> selectTab(1));
        jobsHeading.addView(seeAll);
        homePage.addView(jobsHeading, Ui.matchWrap());
        homePage.addView(Ui.spacer(this, 10));
        homeJobs = new JobList();
        homePage.addView(homeJobs.column, Ui.matchWrap());
        homeEmpty = emptyCard(false);
        homePage.addView(homeEmpty, Ui.matchWrap());

        linkInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                session.input = editable.toString();
                if (internalTextChange) return;
                if (session.details != null && !session.input.trim().equals(session.requestUrl)) {
                    session.details = null;
                    session.selectedQualityId = null;
                    session.message = "";
                    renderAnalysis(false);
                } else if (!session.analyzing && !empty(session.message)) {
                    session.message = "";
                    renderAnalysis(false);
                }
            }
        });
    }

    private void buildLibrary() {
        TextView title = Ui.text(this, "Your downloads", 28, Ui.TEXT, true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heading(title);
        libraryPage.addView(title, Ui.matchWrap());
        libraryPage.addView(Ui.spacer(this, 8));
        librarySummary = Ui.text(this, "Saved and unfinished downloads, all in one place.", 13, Ui.MUTED, false);
        libraryPage.addView(librarySummary, Ui.matchWrap());
        libraryPage.addView(Ui.spacer(this, 22));
        libraryJobs = new JobList();
        libraryPage.addView(libraryJobs.column, Ui.matchWrap());
        libraryEmpty = emptyCard(true);
        libraryPage.addView(libraryEmpty, Ui.matchWrap());
        TextView location = Ui.text(this, Build.VERSION.SDK_INT >= 29
                ? "Completed files are saved to Downloads / StreamSave."
                : "Files are kept in app storage. Use Share to send or save a completed file.",
                11.5f, Ui.QUIET, false);
        location.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams locationParams = Ui.matchWrap();
        locationParams.setMargins(dp(14), dp(16), dp(14), 0);
        libraryPage.addView(location, locationParams);
    }

    private LinearLayout emptyCard(boolean full) {
        LinearLayout panel = card(full ? 28 : 22);
        panel.setGravity(Gravity.CENTER);
        Ui.IconView icon = new Ui.IconView(this, "folder", Ui.MUTED);
        icon.size(full ? 30 : 25);
        icon.setBackground(Ui.shape(this, Ui.SURFACE_HIGH, 18, Color.TRANSPARENT));
        panel.addView(icon, new LinearLayout.LayoutParams(dp(full ? 64 : 52), dp(full ? 64 : 52)));
        panel.addView(Ui.spacer(this, 15));
        TextView title = Ui.text(this, full ? "A home for your videos" : "Ready when you are", full ? 18 : 15, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, Ui.matchWrap());
        panel.addView(Ui.spacer(this, 7));
        TextView body = Ui.text(this, full ? "Start a download and follow its progress here.\nPaused downloads stay ready to resume."
                : "Add a link above to start your first download.", 12.5f, Ui.MUTED, false);
        body.setGravity(Gravity.CENTER);
        panel.addView(body, Ui.matchWrap());
        if (full) {
            panel.addView(Ui.spacer(this, 22));
            Button start = Ui.button(this, "Add a video", true);
            Ui.buttonIcon(start, "plus", Ui.INK);
            start.setOnClickListener(view -> { selectTab(0); linkInput.requestFocus(); });
            panel.addView(start, Ui.matchWrap());
        }
        return panel;
    }

    private LinearLayout card(int padding) {
        LinearLayout view = Ui.vertical(this);
        view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        view.setBackground(Ui.shape(this, Ui.SURFACE, 20, Ui.BORDER));
        return view;
    }

    private ProgressBar spinner() {
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.MINT));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return progress;
    }

    private void addFeature(LinearLayout row, String icon, String title) {
        LinearLayout cell = Ui.horizontal(this);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(5), 0, dp(5), 0);
        Ui.IconView image = new Ui.IconView(this, icon, Ui.MINT);
        image.size(13);
        cell.addView(image, new LinearLayout.LayoutParams(dp(17), dp(17)));
        TextView text = Ui.text(this, title, 11.5f, Ui.MUTED, false);
        text.setPadding(dp(4), 0, 0, 0);
        cell.addView(text, Ui.weight());
        row.addView(cell, Ui.weight());
    }

    private Button textButton(String title, int color) {
        Button view = Ui.button(this, title, false);
        view.setTextSize(12);
        view.setTextColor(color);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(Ui.ripple(this, Color.TRANSPARENT, 10, Color.TRANSPARENT));
        return view;
    }

    private void selectTab(int value) {
        tab = value == 1 ? 1 : 0;
        boolean home = tab == 0;
        homeScroll.setVisibility(home ? View.VISIBLE : View.GONE);
        libraryScroll.setVisibility(home ? View.GONE : View.VISIBLE);
        styleTab(downloadTab, home);
        styleTab(libraryTab, !home);
        if (!home) hideKeyboard();
    }

    private void styleTab(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? Ui.TEXT : Ui.MUTED);
        view.setBackground(Ui.ripple(this, selected ? Ui.SURFACE_HIGH : Color.TRANSPARENT, 12,
                selected ? Ui.BORDER : Color.TRANSPARENT));
    }

    private void renderAppState() {
        if (isFinishing() || isDestroyed() || engineStatus == null) return;
        boolean ready = app.ready;
        boolean initializing = app.initializing;
        boolean failed = !ready && !initializing && !empty(app.initializationError);
        engineStatus.setText(ready ? "●  Ready" : failed ? "Setup needed" : "Starting");
        engineStatus.setTextColor(ready ? Ui.MINT : failed ? Ui.RED : Ui.AMBER);
        engineStatus.setBackground(Ui.shape(this, ready ? Ui.MINT_DARK : Ui.SURFACE_HIGH, 100, Color.TRANSPARENT));
        engineBanner.setVisibility(ready ? View.GONE : View.VISIBLE);
        engineSpinner.setVisibility(initializing ? View.VISIBLE : View.GONE);
        retryEngineButton.setVisibility(failed ? View.VISIBLE : View.GONE);
        engineMessage.setText(failed ? clean(app.initializationError)
                : empty(app.engineStatus) ? "Preparing the download engine. This may take a moment the first time." : app.engineStatus);
        engineMessage.setTextColor(failed ? Ui.RED : Ui.MUTED);
        Ui.enabled(findButton, ready && !session.analyzing);
        Ui.enabled(downloadButton, ready && session.details != null && selectedQuality() != null);
        renderJobs();
    }

    @SuppressLint("SetTextI18n") // This first release's interface is explicitly English.
    private void renderJobs() {
        List<Job> all = new ArrayList<>(app.jobs());
        List<Job> recent = new ArrayList<>();
        int complete = 0;
        int active = 0;
        int paused = 0;
        for (Job job : all) {
            if (job.state == State.COMPLETED) complete++;
            else if (job.state == State.PAUSED) paused++;
            else if (job.state != State.FAILED) active++;
            if (job.state != State.COMPLETED && recent.size() < 3) recent.add(job);
        }
        if (recent.isEmpty()) for (Job job : all) {
            if (recent.size() == 2) break;
            recent.add(job);
        }
        homeJobs.update(recent);
        libraryJobs.update(all);
        homeEmpty.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
        libraryEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        libraryTab.setText(all.isEmpty() ? "Library" : "Library  ·  " + all.size());
        if (all.isEmpty()) librarySummary.setText("Saved and unfinished downloads, all in one place.");
        else {
            String summary = complete + " saved";
            if (active > 0) summary += "  ·  " + active + " in progress";
            if (paused > 0) summary += "  ·  " + paused + " paused";
            int failed = all.size() - complete - active - paused;
            if (failed > 0) summary += "  ·  " + failed + " need attention";
            librarySummary.setText(summary);
        }
    }

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) { toast("Copy a video link first."); return; }
        CharSequence content = data.getItemAt(0).coerceToText(this);
        if (content == null || content.toString().trim().isEmpty()) { toast("The clipboard does not contain a link."); return; }
        setInput(content.toString().trim());
        hideKeyboard();
        toast("Link pasted");
    }

    private void setInput(String value) {
        session.token++;
        session.analyzing = false;
        session.details = null;
        session.message = "";
        session.selectedQualityId = null;
        session.input = value;
        internalTextChange = true;
        linkInput.setText(value);
        linkInput.setSelection(linkInput.getText().length());
        internalTextChange = false;
        renderAnalysis(false);
    }

    private void startAnalysis() {
        if (session.analyzing) return;
        String input = linkInput.getText().toString().trim();
        if (input.isEmpty()) {
            session.message = "Paste a YouTube video link to get started.";
            renderAnalysis(false);
            linkInput.requestFocus();
            return;
        }
        try { App.normalizeUrl(input); }
        catch (IllegalArgumentException invalid) {
            session.message = clean(invalid.getMessage());
            renderAnalysis(false);
            return;
        }
        if (!app.ready) {
            session.message = app.initializing ? "The download engine is still getting ready. Try again in a moment."
                    : "Set up the download engine above, then try this link again.";
            renderAnalysis(false);
            return;
        }
        hideKeyboard();
        linkInput.clearFocus();
        session.requestUrl = input;
        session.input = input;
        session.details = null;
        session.selectedQualityId = null;
        session.message = "";
        session.analyzing = true;
        int token = ++session.token;
        renderAnalysis(false);
        try {
            app.analyze(input, new LookupCallback(session, token));
        } catch (RuntimeException error) {
            new LookupCallback(session, token).failure("Could not start this lookup. " + safeError(error));
        }
    }

    private void renderAnalysis(boolean reveal) {
        if (videoCard == null) return;
        boolean analyzing = session.analyzing;
        findButton.setText(analyzing ? "Finding available qualities…" : "Find video");
        Ui.enabled(findButton, app.ready && !analyzing);
        Ui.enabled(pasteButton, !analyzing);
        linkInput.setEnabled(!analyzing);
        linkInput.setAlpha(analyzing ? 0.65f : 1f);
        analysisSpinner.setVisibility(analyzing ? View.VISIBLE : View.GONE);
        analysisMessage.setText(analyzing ? "Reading video details and the available source formats…" : clean(session.message));
        analysisMessage.setTextColor(analyzing ? Ui.MUTED : Ui.RED);
        analysisRow.setVisibility(analyzing || !empty(session.message) ? View.VISIBLE : View.GONE);
        VideoDetails details = session.details;
        videoCard.setVisibility(details == null ? View.GONE : View.VISIBLE);
        if (details == null) { clearQualityReveal(); Ui.enabled(downloadButton, false); return; }
        videoTitle.setText(empty(details.title) ? "YouTube video" : details.title);
        String metadata = clean(details.uploader);
        if (!empty(details.durationLabel)) metadata += (metadata.isEmpty() ? "" : "  ·  ") + details.durationLabel;
        videoMeta.setText(metadata);
        videoMeta.setVisibility(metadata.isEmpty() ? View.GONE : View.VISIBLE);
        if (selectedQuality() == null && details.qualities != null && !details.qualities.isEmpty()) {
            session.selectedQualityId = details.qualities.get(0).id;
        }
        qualityList.removeAllViews();
        if (details.qualities != null) for (Quality quality : details.qualities) {
            QualityRow row = new QualityRow(quality);
            LinearLayout.LayoutParams params = Ui.matchWrap();
            params.bottomMargin = dp(8);
            qualityList.addView(row, params);
        }
        renderQualitySelection();
        if (reveal) {
            videoCard.setAlpha(0f);
            videoCard.animate().alpha(1f).setDuration(180).start();
            revealQualityAfterLayout();
        }
    }

    private void revealQualityAfterLayout() {
        clearQualityReveal();
        pendingQualityReveal = () -> {
            if (isDestroyed() || isFinishing() || session.details == null) { clearQualityReveal(); return; }
            if (homeScroll.getVisibility() != View.VISIBLE || videoCard.getHeight() == 0 || videoCard.isLayoutRequested()) return;
            clearQualityReveal();
            homeScroll.smoothScrollTo(0, Math.max(0, videoCard.getTop() - dp(16)));
        };
        // A posted runnable alone can run before the GONE-to-VISIBLE card has been laid out.
        homePage.getViewTreeObserver().addOnGlobalLayoutListener(pendingQualityReveal);
        homePage.requestLayout();
    }

    private void clearQualityReveal() {
        if (pendingQualityReveal == null || homePage == null) return;
        ViewTreeObserver observer = homePage.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(pendingQualityReveal);
        pendingQualityReveal = null;
    }

    private Quality selectedQuality() {
        if (session == null || session.details == null || session.details.qualities == null) return null;
        for (Quality quality : session.details.qualities) {
            if (same(quality.id, session.selectedQualityId)) return quality;
        }
        return null;
    }

    private void renderQualitySelection() {
        for (int i = 0; i < qualityList.getChildCount(); i++) {
            View child = qualityList.getChildAt(i);
            if (child instanceof QualityRow) ((QualityRow) child).updateSelected();
        }
        Quality selected = selectedQuality();
        downloadButton.setText(selected == null ? "Download" : "Download  ·  " + clean(selected.label));
        Ui.enabled(downloadButton, app.ready && selected != null);
    }

    private void enqueueSelected() {
        Quality quality = selectedQuality();
        VideoDetails video = session.details;
        if (quality == null || video == null || !app.ready) return;
        String key = clean(video.url) + ":" + clean(quality.id);
        long now = SystemClock.elapsedRealtime();
        if (same(lastEnqueueKey, key) && now - lastEnqueueTime < 1400) return;
        lastEnqueueKey = key;
        lastEnqueueTime = now;
        try {
            app.enqueue(video, quality);
            requestNotificationsOnce();
            renderJobs();
            selectTab(1);
            libraryScroll.smoothScrollTo(0, 0);
        } catch (RuntimeException error) {
            showMessage("Could not start the download", safeError(error));
        }
    }

    private void requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("notifications_asked", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
    }

    private void showSettings(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 5, 0, "Update StreamSave app");
        menu.getMenu().add(0, 1, 1, session.updating ? "Updating download engine…" : "Update download engine")
                .setEnabled(!session.updating && !app.initializing);
        if (!app.ready && !app.initializing) menu.getMenu().add(0, 2, 1, "Retry engine setup");
        menu.getMenu().add(0, 3, 2, "How downloads work");
        menu.getMenu().add(0, 4, 3, "About StreamSave");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 5) updateStreamSaveApp();
            else if (item.getItemId() == 1) updateEngine();
            else if (item.getItemId() == 2) { app.initEngine(); renderAppState(); }
            else if (item.getItemId() == 3) showDownloadHelp();
            else if (item.getItemId() == 4) showAbout();
            return true;
        });
        menu.show();
    }


    private void updateStreamSaveApp() {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("StreamSave app update")
                .setMessage("Checking the latest signed version…")
                .setNegativeButton("Close", null)
                .create();
        styleAndShow(progress);
        AppUpdater.checkAndDownload(this, new AppUpdater.Callback() {
            @Override public void progress(String message) {
                if (!isFinishing() && !isDestroyed() && progress.isShowing()) progress.setMessage(message);
            }

            @Override public void upToDate(String message) {
                if (!isFinishing() && !isDestroyed()) {
                    if (progress.isShowing()) progress.setMessage(message);
                    else showMessage("StreamSave app update", message);
                }
            }

            @Override public void ready(String message) {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                // After the first permission grant Android returns to StreamSave.
                // Then open the system installer without making the user download again.
                installUpdateAfterPermission = Build.VERSION.SDK_INT >= 26
                        && !getPackageManager().canRequestPackageInstalls();
                AppUpdater.install(MainActivity.this);
            }

            @Override public void failed(String message) {
                if (!isFinishing() && !isDestroyed()) {
                    if (progress.isShowing()) progress.setMessage(message);
                    else showMessage("StreamSave app update", message);
                }
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (installUpdateAfterPermission && (Build.VERSION.SDK_INT < 26
                || getPackageManager().canRequestPackageInstalls())) {
            installUpdateAfterPermission = false;
            AppUpdater.install(this);
        }
    }

    private void updateEngine() {
        if (session.updating) return;
        session.updating = true;
        toast("Checking for a download engine update…");
        final Session target = session;
        try {
            app.updateEngine((message, success) -> {
                target.updating = false;
                MainActivity current = target.owner.get();
                if (current != null) current.runOnUiThread(() -> {
                    if (current.isDestroyed() || current.isFinishing()) return;
                    current.renderAppState();
                    current.showMessage(success ? "Download engine ready" : "Engine update", clean(message));
                });
            });
        } catch (RuntimeException error) {
            session.updating = false;
            showMessage("Engine update", safeError(error));
        }
    }

    private void showDownloadHelp() {
        String location = Build.VERSION.SDK_INT >= 29
                ? "Completed files appear in Downloads / StreamSave. Use Open to play a file, or Share to send it."
                : "Completed files stay in app storage. Use Open to play, or Share to send or save them with another app. Uninstalling StreamSave removes these app-stored files.";
        showMessage("Make it yours", "1. Paste a YouTube video link, or share a video to StreamSave.\n\n"
                + "2. Tap Find video, choose one of its available qualities, then download.\n\n"
                + "Pause keeps unfinished files. Resume continues from those files when the source supports it. Retry is available after a failed download.\n\n"
                + "Parallel connections are used when the source supports them. Your selected quality stays the same.\n\n"
                + "Video and audio can download separately; the percentage shows progress for the current stream. They are combined without re-encoding. Some qualities use MKV; your player must support the selected format.\n\n"
                + location + "\n\nIf a link stops working, try Update download engine in the settings menu.");
    }

    private void showAbout() {
        String version = "1.0";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { }
        showMessage("StreamSave " + version, "A focused home for your video downloads.\n\n"
                + "StreamSave is free software, licensed under the GNU General Public License v3.0. Full source code and license texts are included with the source package.\n\n"
                + "Built with yt-dlp, youtubedl-android and FFmpeg.\n\n"
                + "Available quality depends on the original video. Download speed depends on your connection and the video source.\n\n"
                + "StreamSave is independent and is not affiliated with YouTube.");
    }

    private void showJobMenu(View anchor, Job job) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Copy video link");
        if (job.state == State.FAILED && !empty(job.message)) menu.getMenu().add(0, 2, 1, "View error details");
        if (job.state != State.MERGING && job.state != State.SAVING) {
            menu.getMenu().add(0, 3, 2, job.state == State.COMPLETED ? "Remove from list" : "Remove download");
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Video link", clean(job.url)));
                toast("Video link copied");
            } else if (item.getItemId() == 2) showMessage("Download details", clean(job.message));
            else if (item.getItemId() == 3) confirmRemove(job);
            return true;
        });
        menu.show();
    }

    private void confirmRemove(Job job) {
        boolean complete = job.state == State.COMPLETED;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(complete ? "Remove from list?" : "Remove this download?")
                .setMessage(complete ? "The saved file will stay on your device. Only this item will be removed from StreamSave."
                        : "This will cancel the download and remove its unfinished files.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", (d, which) -> {
                    try { app.remove(job.id); renderJobs(); }
                    catch (RuntimeException error) { showMessage("Could not remove download", safeError(error)); }
                }).create();
        styleAndShow(dialog);
    }

    private void showMessage(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title)
                .setMessage(empty(message) ? "Please try again." : message).setPositiveButton("OK", null).create();
        styleAndShow(dialog);
    }

    private void styleAndShow(AlertDialog dialog) {
        dialog.setOnShowListener(which -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.MINT);
            if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Ui.MUTED);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(Ui.shape(this, Ui.SURFACE, 23, Ui.BORDER));
        });
        dialog.show();
    }

    private void readIncomingIntent(Intent intent) {
        if (intent == null) return;
        String value = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null) value = shared.toString();
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) value = intent.getData().toString();
        if (!empty(value)) {
            setInput(value.trim());
            selectTab(0);
            homeScroll.smoothScrollTo(0, 0);
            toast("Shared link ready. Tap Find video to choose a quality.");
        } else if (!Intent.ACTION_SEND.equals(intent.getAction()) && intent.getBooleanExtra("show_library", false)) {
            // Consume this navigation request so a later rotation preserves the user's chosen tab.
            intent.removeExtra("show_library");
            selectTab(1);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIncomingIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        app.addListener(appListener);
        renderAppState();
        renderAnalysis(false);
    }

    @Override protected void onStop() {
        app.removeListener(appListener);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("input", linkInput.getText().toString());
        out.putString("quality", session.selectedQualityId);
        out.putInt("tab", tab);
        super.onSaveInstanceState(out);
    }

    @Override public Object onRetainNonConfigurationInstance() { return session; }

    @Override protected void onDestroy() {
        clearQualityReveal();
        if (session.owner.get() == this) session.owner.clear();
        super.onDestroy();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null && root != null) manager.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Ui.dp(this, value); }
    private void heading(View view) { if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String safeError(Throwable error) { return empty(error.getMessage()) ? "Please try again." : error.getMessage(); }

    private static final class Session {
        volatile WeakReference<MainActivity> owner = new WeakReference<>(null);
        volatile VideoDetails details;
        volatile int token;
        volatile boolean analyzing;
        volatile boolean updating;
        String input = "";
        String requestUrl = "";
        String selectedQualityId;
        volatile String message = "";
    }

    private static final class LookupCallback implements AnalysisCallback {
        private final Session session;
        private final int token;
        LookupCallback(Session session, int token) { this.session = session; this.token = token; }
        @Override public void success(VideoDetails details) {
            if (details == null || details.qualities == null || details.qualities.isEmpty()) {
                failure("No downloadable qualities were found for this video. Try updating the download engine.");
                return;
            }
            finish(details, "");
        }
        @Override public void failure(String message) { finish(null, empty(message) ? "Could not load this video. Please try again." : message); }
        private void finish(VideoDetails details, String message) {
            if (session.token != token) return;
            session.details = details;
            session.message = message;
            session.analyzing = false;
            MainActivity activity = session.owner.get();
            if (activity != null) activity.runOnUiThread(() -> {
                if (!activity.isDestroyed() && !activity.isFinishing() && session.token == token) {
                    activity.renderAnalysis(details != null);
                }
            });
        }
    }

    private final class QualityRow extends LinearLayout {
        final Quality quality;
        final TextView check;
        QualityRow(Quality quality) {
            super(MainActivity.this);
            this.quality = quality;
            setTag("quality-" + quality.id);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(65));
            setPadding(dp(15), dp(13), dp(14), dp(13));
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            LinearLayout labels = Ui.vertical(MainActivity.this);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            labels.addView(Ui.text(MainActivity.this, clean(quality.label), 14, Ui.TEXT, true), Ui.matchWrap());
            if (!empty(quality.detail)) {
                TextView detail = Ui.text(MainActivity.this, quality.detail, 11.5f, Ui.MUTED, false);
                LinearLayout.LayoutParams params = Ui.matchWrap();
                params.topMargin = dp(5);
                labels.addView(detail, params);
            }
            addView(labels, Ui.weight());
            check = Ui.text(MainActivity.this, "", 13, Ui.INK, true);
            check.setGravity(Gravity.CENTER);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(23), dp(23));
            checkParams.setMarginStart(dp(10));
            addView(check, checkParams);
            setOnClickListener(view -> {
                session.selectedQualityId = quality.id;
                renderQualitySelection();
            });
        }

        void updateSelected() {
            boolean selected = same(quality.id, session.selectedQualityId);
            setSelected(selected);
            setBackground(Ui.ripple(MainActivity.this, selected ? Ui.MINT_DARK : Ui.BG, 13, selected ? Ui.MINT : Ui.BORDER));
            check.setText(selected ? "✓" : "");
            check.setBackground(Ui.shape(MainActivity.this, selected ? Ui.MINT : Color.TRANSPARENT, 100,
                    selected ? Color.TRANSPARENT : Ui.QUIET));
            setContentDescription(clean(quality.label) + (empty(quality.detail) ? "" : ", " + quality.detail));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }

    private final class JobList {
        final LinearLayout column = Ui.vertical(MainActivity.this);
        final Map<String, JobCard> cards = new HashMap<>();
        final List<String> ids = new ArrayList<>();

        void update(List<Job> jobs) {
            List<String> next = new ArrayList<>();
            for (Job job : jobs) next.add(job.id);
            if (!next.equals(ids)) {
                column.removeAllViews();
                cards.keySet().retainAll(next);
                for (Job job : jobs) {
                    JobCard card = cards.get(job.id);
                    if (card == null) { card = new JobCard(job); cards.put(job.id, card); }
                    LinearLayout.LayoutParams params = Ui.matchWrap();
                    params.bottomMargin = dp(12);
                    column.addView(card.panel, params);
                }
                ids.clear();
                ids.addAll(next);
            }
            for (Job job : jobs) {
                JobCard card = cards.get(job.id);
                if (card != null) card.bind(job);
            }
            column.setVisibility(jobs.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private final class JobCard {
        final LinearLayout panel;
        final TextView title;
        final TextView quality;
        final TextView status;
        final TextView percent;
        final TextView detail;
        final TextView error;
        final Ui.ProgressTrack progress;
        final LinearLayout buttons;
        State previousState;
        Job current;

        JobCard(Job job) {
            current = job;
            panel = card(16);
            LinearLayout top = Ui.horizontal(MainActivity.this);
            top.setGravity(Gravity.TOP);
            Ui.IconView icon = new Ui.IconView(MainActivity.this,
                    clean(job.qualityLabel).toLowerCase(Locale.ROOT).contains("audio") ? "music" : "download", Ui.MINT);
            icon.size(20);
            icon.setBackground(Ui.shape(MainActivity.this, Ui.SURFACE_HIGH, 12, Color.TRANSPARENT));
            top.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout textBlock = Ui.vertical(MainActivity.this);
            textBlock.setPadding(dp(12), dp(1), dp(2), 0);
            title = Ui.text(MainActivity.this, "", 14, Ui.TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textBlock.addView(title, Ui.matchWrap());
            quality = Ui.text(MainActivity.this, "", 11.5f, Ui.MUTED, false);
            LinearLayout.LayoutParams qualityParams = Ui.matchWrap();
            qualityParams.topMargin = dp(6);
            textBlock.addView(quality, qualityParams);
            top.addView(textBlock, Ui.weight());
            Ui.IconView more = Ui.iconButton(MainActivity.this, "more", "Download options");
            more.setOnClickListener(view -> showJobMenu(view, current));
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(40), dp(48));
            moreParams.setMarginEnd(dp(-9));
            moreParams.topMargin = dp(-6);
            top.addView(more, moreParams);
            panel.addView(top, Ui.matchWrap());
            panel.addView(Ui.spacer(MainActivity.this, 16));
            LinearLayout stateRow = Ui.horizontal(MainActivity.this);
            status = Ui.text(MainActivity.this, "", 11.5f, Ui.MINT, true);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            stateRow.addView(status, Ui.weight());
            percent = Ui.text(MainActivity.this, "", 11.5f, Ui.MUTED, true);
            stateRow.addView(percent);
            panel.addView(stateRow, Ui.matchWrap());
            progress = new Ui.ProgressTrack(MainActivity.this);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
            progressParams.topMargin = dp(10);
            panel.addView(progress, progressParams);
            detail = Ui.text(MainActivity.this, "", 11.5f, Ui.MUTED, false);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailParams = Ui.matchWrap();
            detailParams.topMargin = dp(9);
            panel.addView(detail, detailParams);
            error = Ui.text(MainActivity.this, "", 12, Ui.RED, false);
            error.setMaxLines(3);
            error.setEllipsize(TextUtils.TruncateAt.END);
            error.setOnClickListener(view -> showMessage("Download details", clean(current.message)));
            LinearLayout.LayoutParams errorParams = Ui.matchWrap();
            errorParams.topMargin = dp(9);
            panel.addView(error, errorParams);
            buttons = Ui.horizontal(MainActivity.this);
            LinearLayout.LayoutParams buttonParams = Ui.matchWrap();
            buttonParams.topMargin = dp(14);
            panel.addView(buttons, buttonParams);
        }

        void bind(Job job) {
            current = job;
            title.setText(empty(job.title) ? "Video download" : job.title);
            quality.setText(clean(job.qualityLabel));
            State state = job.state;
            int color = state == State.FAILED ? Ui.RED : state == State.PAUSED ? Ui.AMBER : Ui.MINT;
            status.setText(statusTitle(state));
            status.setTextColor(color);
            float value = Float.isNaN(job.progress) || Float.isInfinite(job.progress) ? 0 : Math.max(0, Math.min(100, job.progress));
            boolean determinate = state == State.DOWNLOADING || state == State.PAUSED;
            percent.setText(determinate ? String.format(Locale.US, "Stream %.1f%%", value).replace(".0%", "%") : "");
            progress.setVisibility(state == State.COMPLETED ? View.GONE : View.VISIBLE);
            progress.update(value, color, state == State.MERGING || state == State.SAVING);
            String line = clean(job.statusLine);
            if (line.isEmpty()) {
                if (state == State.QUEUED) line = "Waiting for the current download";
                else if (state == State.PAUSED) line = "Your unfinished files are kept for resume";
                else if (state == State.MERGING) line = "Combining video and audio at original quality";
                else if (state == State.SAVING) line = "Moving the finished file to your downloads";
                else if (state == State.COMPLETED) line = "Ready to open or share";
            }
            detail.setText(line);
            detail.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
            error.setText(clean(job.message));
            error.setVisibility(state == State.FAILED && !empty(job.message) ? View.VISIBLE : View.GONE);
            if (previousState != state) {
                previousState = state;
                buildActions(job);
            }
        }

        private void buildActions(Job job) {
            buttons.removeAllViews();
            State state = job.state;
            if (state == State.COMPLETED) {
                addAction("Open", "play", true, () -> app.open(MainActivity.this, current.id));
                if (!current.audioOnly) addAction("Study", "play", false, () -> {
                    Intent study = new Intent(MainActivity.this, PlayerActivity.class);
                    study.putExtra(PlayerActivity.EXTRA_URI, current.contentUri);
                    study.putExtra(PlayerActivity.EXTRA_KEY, "job:" + current.id);
                    study.putExtra(PlayerActivity.EXTRA_TITLE, current.title);
                    startActivity(study);
                });
                addAction("Share", "share", false, () -> app.share(MainActivity.this, current.id));
            } else if (state == State.DOWNLOADING || state == State.QUEUED) {
                addAction("Pause", "pause", false, () -> app.pause(current.id));
            } else if (state == State.PAUSED) {
                addAction("Resume", "play", true, () -> { app.resume(current.id); requestNotificationsOnce(); });
            } else if (state == State.FAILED) {
                addAction("Retry", "refresh", true, () -> { app.resume(current.id); requestNotificationsOnce(); });
                addAction("Details", "info", false, () -> showMessage("Download details", clean(current.message)));
            }
            buttons.setVisibility(buttons.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }

        private void addAction(String label, String icon, boolean primary, Runnable action) {
            Button button = Ui.button(MainActivity.this, label, primary);
            button.setTextSize(12.5f);
            Ui.buttonIcon(button, icon, primary ? Ui.INK : Ui.TEXT);
            button.setOnClickListener(view -> {
                try { action.run(); renderJobs(); }
                catch (RuntimeException failure) { showMessage("Download action", safeError(failure)); }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (buttons.getChildCount() > 0) params.setMarginStart(dp(9));
            buttons.addView(button, params);
        }

        private String statusTitle(State state) {
            if (state == null) return "Waiting";
            switch (state) {
                case QUEUED: return "Queued";
                case DOWNLOADING: return "Downloading";
                case PAUSED: return "Paused";
                case MERGING: return "Combining video + audio";
                case SAVING: return "Saving your file";
                case COMPLETED: return "Saved";
                case FAILED: return "Needs attention";
                default: return "Waiting";
            }
        }
    }
}
