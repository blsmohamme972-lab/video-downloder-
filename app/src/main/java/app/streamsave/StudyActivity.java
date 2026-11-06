package app.streamsave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import android.media.MediaPlayer;

import java.io.InputStream;
import java.util.Locale;

/**
 * Surgical Studio: video stays untouched, annotation ZIP is parsed as data,
 * and cues are drawn over the native Android player during playback.
 */
public final class StudyActivity extends Activity {
    private static final int PICK_VIDEO = 201;
    private static final int PICK_ZIP = 202;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VideoView video;
    private StudyOverlay overlay;
    private TextView subtitle, clock;
    private SeekBar seek;
    private Button play, importZip, toggle;
    private boolean dragging;
    private boolean visible = true;
    private Uri videoUri;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (video != null) {
                int position = Math.max(0, video.getCurrentPosition());
                if (!dragging) seek.setProgress(position);
                int duration = Math.max(0, video.getDuration());
                if (duration > 0) seek.setMax(duration);
                overlay.setTimeMs(position);
                clock.setText(time(position) + " / " + time(duration));
                play.setText(video.isPlaying() ? "Pause" : "Play");
                handler.postDelayed(this, 70);
            }
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = Ui.vertical(this);
        root.setPadding(dp(18), dp(15), dp(18), dp(16));
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        TextView heading = Ui.text(this, "Surgical Studio", 27, Ui.TEXT, true);
        root.addView(heading, Ui.matchWrap());
        root.addView(Ui.spacer(this, 6));
        subtitle = Ui.text(this, "Open a video and import its AI annotation ZIP.", 13, Ui.MUTED, false);
        root.addView(subtitle, Ui.matchWrap());
        root.addView(Ui.spacer(this, 16));

        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(Ui.shape(this, Color.BLACK, 16, Color.TRANSPARENT));
        video = new VideoView(this);
        video.setBackgroundColor(Color.TRANSPARENT);
        stage.addView(video, new FrameLayout.LayoutParams(-1,-1));
        overlay = new StudyOverlay(this);
        overlay.setClickable(false);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1,0,1);
        root.addView(stage, stageParams);
        root.addView(Ui.spacer(this, 12));
        seek = new SeekBar(this);
        seek.setMax(1);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if (fromUser) { clock.setText(time(value)); overlay.setTimeMs(value); }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                video.seekTo(bar.getProgress()); overlay.setTimeMs(bar.getProgress()); dragging=false;
            }
        });
        root.addView(seek, Ui.matchWrap());
        clock = Ui.text(this, "00:00 / 00:00", 12, Ui.MUTED, false);
        clock.setGravity(Gravity.CENTER);
        root.addView(clock, Ui.matchWrap());
        root.addView(Ui.spacer(this, 10));

        LinearLayout actions = Ui.horizontal(this);
        play = Ui.button(this, "Play", true);
        play.setOnClickListener(v -> {
            if (videoUri == null) { pickVideo(); return; }
            if (video.isPlaying()) video.pause(); else video.start();
        });
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button open = Ui.button(this, "Video", false);
        open.setOnClickListener(v -> pickVideo());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0,dp(48),1);
        actionParams.setMarginStart(dp(8));
        actions.addView(open, actionParams);
        importZip = Ui.button(this, "Import ZIP", false);
        importZip.setOnClickListener(v -> pickZip());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0,dp(48),1.2f);
        importParams.setMarginStart(dp(8));
        actions.addView(importZip, importParams);
        root.addView(actions, Ui.matchWrap());
        root.addView(Ui.spacer(this, 10));

        toggle = Ui.button(this, "Hide overlays", false);
        toggle.setOnClickListener(v -> {
            visible = !visible;
            overlay.setAnnotationsEnabled(visible);
            toggle.setText(visible ? "Hide overlays" : "Show overlays");
        });
        root.addView(toggle, Ui.matchWrap());
        TextView notice = Ui.text(this,
                "Educational use only. AI-generated surgical labels may be incorrect. " +
                "Review anatomy, timing and clinical claims before relying on an annotation.",
                11, Ui.QUIET, false);
        LinearLayout.LayoutParams noticeParams = Ui.matchWrap();
        noticeParams.topMargin = dp(12);
        root.addView(notice, noticeParams);

        String savedUri = getIntent().getStringExtra("video_uri");
        if (savedUri != null && !savedUri.isEmpty()) openVideo(Uri.parse(savedUri));
        else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri shared = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            String mime = getIntent().getType();
            if (shared != null && mime != null && mime.startsWith("video/")) openVideo(shared);
        }
        handler.post(tick);
    }

    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, PICK_VIDEO);
    }

    private void pickZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, PICK_ZIP);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_VIDEO) {
            openVideo(uri);
        } else if (request == PICK_ZIP) {
            // Packages are explicitly imported for the currently selected video.
            if (videoUri == null) { message("Select a video before importing its annotation ZIP."); return; }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                StudyPackage pkg = StudyPackage.read(input);
                overlay.setStudy(pkg);
                subtitle.setText(pkg.title + "  •  " + pkg.cues.size() + " synchronized overlays");
                message("Imported " + pkg.cues.size() + " annotations. Video was not modified.");
            } catch (Exception error) {
                message("Cannot import annotation ZIP: " + error.getMessage());
            }
        }
    }

    private void openVideo(Uri uri) {
        videoUri = uri;
        overlay.setStudy(null); // Do not carry annotations to an unrelated video.
        subtitle.setText("Video ready. Import its matching annotation ZIP.");
        video.setOnPreparedListener(player -> {
            player.setOnVideoSizeChangedListener((mp, width, height) -> resizeOverlay(width, height));
            resizeOverlay(player.getVideoWidth(), player.getVideoHeight());
            seek.setMax(Math.max(1, player.getDuration()));
        });
        video.setOnErrorListener((player, what, extra) -> {
            message("This video codec could not be played by the Android system player.");
            return true;
        });
        video.setVideoURI(uri);
        video.requestFocus();
    }

    private void resizeOverlay(int videoWidth, int videoHeight) {
        FrameLayout parent = (FrameLayout) overlay.getParent();
        if (parent == null || videoWidth < 1 || videoHeight < 1) return;
        int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw < 1 || ph < 1) {
            parent.post(() -> resizeOverlay(videoWidth, videoHeight));
            return;
        }
        // VideoView fits the video inside the stage; use the exact same centered content rect.
        float scale = Math.min((float)pw / videoWidth, (float)ph / videoHeight);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1,Math.round(videoWidth * scale)), Math.max(1,Math.round(videoHeight * scale)), Gravity.CENTER);
        overlay.setLayoutParams(params);
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setTitle("Surgical Studio").setMessage(text)
                .setPositiveButton("OK",null).show();
    }

    private String time(long ms) {
        long sec = Math.max(0,ms)/1000;
        return String.format(Locale.US,"%02d:%02d:%02d",sec/3600,sec/60%60,sec%60);
    }
    private int dp(float value) { return Ui.dp(this,value); }
    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
