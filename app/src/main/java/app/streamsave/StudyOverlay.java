package app.streamsave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Real-time, synchronized, hardware-independent overlay; original video is never changed. */
final class StudyOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private StudyPackage study;
    private long timeMs;
    private boolean enabled = true;

    StudyOverlay(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

    void setStudy(StudyPackage next) {
        for (Bitmap bitmap : bitmaps.values()) bitmap.recycle();
        bitmaps.clear();
        study = next;
        if (next != null) {
            int decoded = 0;
            for (Map.Entry<String, byte[]> entry : next.images.entrySet()) {
                if (decoded >= 8) break;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(entry.getValue(), 0, entry.getValue().length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 1024
                        || options.outHeight > 1024) continue;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(entry.getValue(), 0, entry.getValue().length, options);
                if (bmp != null) { bitmaps.put(entry.getKey(), bmp); decoded++; }
            }
        }
        invalidate();
    }

    void setTimeMs(long value) { timeMs = value; invalidate(); }
    void setAnnotationsEnabled(boolean show) { enabled = show; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled || study == null || getWidth() < 1 || getHeight() < 1) return;
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        int drawn = 0;
        for (StudyPackage.Cue cue : study.cues) {
            if (!cue.active(timeMs)) continue;
            // Limit layers so even malformed educational packages cannot freeze the player.
            if (++drawn > 30) break;
            StudyPackage.Position p = cue.at(timeMs);
            float x = p.x * getWidth(), y = p.y * getHeight();
            float x2 = p.x2 * getWidth(), y2 = p.y2 * getHeight();
            int color = Color.parseColor(cue.color);
            float fade = Math.min(1f, Math.min((timeMs - cue.startMs) / 260f,
                    (cue.endMs - timeMs) / 260f));
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(color);
            paint.setAlpha(Math.max(0, Math.min(255, Math.round(fade * 255))));
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            switch (cue.type) {
                case "arrow":
                    drawArrow(canvas, x, y, x2, y2);
                    break;
                case "ring":
                    float radius = dp(22) + (float) Math.sin(timeMs / 220.0) * dp(4);
                    canvas.drawCircle(x, y, radius, paint);
                    canvas.drawCircle(x, y, dp(3), paint);
                    break;
                case "box":
                    canvas.drawRoundRect(new RectF(Math.min(x,x2), Math.min(y,y2),
                            Math.max(x,x2), Math.max(y,y2)), dp(8), dp(8), paint);
                    break;
                case "image":
                    Bitmap image = bitmaps.get(cue.asset);
                    if (image != null) {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawBitmap(image, null,
                                new RectF(Math.min(x,x2), Math.min(y,y2),
                                    Math.max(x,x2), Math.max(y,y2)), paint);
                    }
                    break;
                case "note":
                case "label":
                    drawText(canvas, cue.text, x, y, color, fade, cue.type.equals("note"));
                    break;
            }
        }
        canvas.restore();
    }

    private void drawArrow(Canvas canvas, float x, float y, float x2, float y2) {
        canvas.drawLine(x, y, x2, y2, paint);
        double angle = Math.atan2(y2 - y, x2 - x);
        float length = dp(14);
        Path path = new Path();
        path.moveTo(x2, y2);
        path.lineTo(x2 - length * (float)Math.cos(angle - 0.55),
                y2 - length * (float)Math.sin(angle - 0.55));
        path.moveTo(x2, y2);
        path.lineTo(x2 - length * (float)Math.cos(angle + 0.55),
                y2 - length * (float)Math.sin(angle + 0.55));
        canvas.drawPath(path, paint);
    }

    private void drawText(Canvas canvas, String text, float x, float y, int color, float fade, boolean note) {
        if (text.isEmpty()) return;
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(dp(note ? 14 : 12));
        paint.setStyle(Paint.Style.FILL);
        float maxWidth = Math.max(dp(90), getWidth() - dp(32));
        float lineHeight = dp(note ? 19 : 17);
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String proposal = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(proposal) > maxWidth - dp(30) && line.length() > 0) {
                    lines.add(line.toString()); line = new StringBuilder(word);
                } else line = new StringBuilder(proposal);
                if (lines.size() >= 5) break;
            }
            if (lines.size() >= 5) break;
            lines.add(line.toString());
        }
        if (lines.isEmpty()) return;
        float boxWidth = dp(80);
        for (String line : lines) boxWidth = Math.max(boxWidth, paint.measureText(line) + dp(22));
        boxWidth = Math.min(maxWidth, boxWidth);
        float height = lines.size() * lineHeight + dp(18);
        float left = Math.max(dp(8), Math.min(getWidth() - boxWidth - dp(8), x - boxWidth / 2));
        float top = Math.max(dp(8), Math.min(getHeight() - height - dp(8), y));
        paint.setColor(Color.rgb(12, 23, 29)); paint.setAlpha((int)(fade * 223));
        canvas.drawRoundRect(new RectF(left, top, left + boxWidth, top + height), dp(11), dp(11), paint);
        paint.setColor(color); paint.setAlpha((int)(fade * 255));
        canvas.drawRoundRect(new RectF(left, top, left + dp(3), top + height), dp(2), dp(2), paint);
        paint.setColor(Color.WHITE);
        for (int i=0;i<lines.size();i++) canvas.drawText(lines.get(i), left + dp(12),
                top + dp(17) + lineHeight * i, paint);
    }

    private float dp(float size) { return size * getResources().getDisplayMetrics().density; }
}
