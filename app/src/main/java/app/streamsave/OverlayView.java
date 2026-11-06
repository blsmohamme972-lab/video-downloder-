package app.streamsave;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a study pack's animated drawings over the exact area the video
 * occupies. Coordinates in the pack are fractions of the video picture, so
 * the activity sizes this view to the video's content rectangle.
 */
final class OverlayView extends View {
    private static final long FADE_MS = 350;
    private static final long POP_MS = 320;
    private static final long DRAW_MS = 700;
    private static final long PULSE_PERIOD_MS = 1100;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.PathMeasure measure = new android.graphics.PathMeasure();
    private final Path part = new Path();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private StudyPack pack;
    private long timeMs;
    private boolean drawingsOn = true;

    OverlayView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        halo.set(stroke);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(context, 1.5f));
        text.setColor(Color.WHITE);
        text.setTextSize(Ui.dp(context, 13));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setPack(StudyPack pack) {
        this.pack = pack;
        for (Bitmap bitmap : bitmaps.values()) bitmap.recycle();
        bitmaps.clear();
        if (pack != null) {
            for (Map.Entry<String, byte[]> entry : pack.images.entrySet()) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(entry.getValue(), 0, entry.getValue().length);
                if (bitmap != null) bitmaps.put(entry.getKey(), bitmap);
            }
        }
        invalidate();
    }

    void setTimeMs(long timeMs) {
        if (this.timeMs == timeMs) return;
        this.timeMs = timeMs;
        if (pack != null && drawingsOn) invalidate();
    }

    void setDrawingsOn(boolean on) {
        drawingsOn = on;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pack == null || !drawingsOn || getWidth() < 2 || getHeight() < 2) return;
        int drawn = 0;
        for (StudyPack.Overlay overlay : pack.overlays) {
            if (overlay.startMs > timeMs) break;
            if (!overlay.active(timeMs)) continue;
            draw(canvas, overlay);
            if (++drawn >= 40) break; // keeps a crowded pack readable and fast
        }
    }

    private void draw(Canvas canvas, StudyPack.Overlay overlay) {
        float w = getWidth(), h = getHeight();
        long age = timeMs - overlay.startMs;
        long left = overlay.endMs - timeMs;
        StudyPack.Position p = overlay.at(timeMs);
        float alpha = overlay.animation.equals("none") ? 1f
                : Math.min(1f, age / (float) FADE_MS) * Math.min(1f, left / (float) FADE_MS);
        float scale = 1f;
        if (overlay.animation.equals("pop") && age < POP_MS) scale = backOut(age / (float) POP_MS);
        if (overlay.animation.equals("pulse")) {
            scale = 1f + 0.09f * (float) Math.sin(2 * Math.PI * (age % PULSE_PERIOD_MS) / PULSE_PERIOD_MS);
        }
        float progress = overlay.animation.equals("draw") ? Math.min(1f, age / (float) DRAW_MS) : 1f;
        int color = Color.parseColor(overlay.color);
        float line = Ui.dp(getContext(), 3.5f);
        stroke.setStrokeWidth(line);
        stroke.setColor(withAlpha(color, alpha));
        // A dark halo keeps bright drawings visible on bright surgical fields.
        halo.setColor(withAlpha(Color.BLACK, alpha * 0.45f));
        halo.setStrokeWidth(line + Ui.dp(getContext(), 2.5f));

        float x = p.x * w, y = p.y * h, x2 = p.x2 * w, y2 = p.y2 * h;
        switch (overlay.type) {
            case "arrow": {
                float ex = x + (x2 - x) * progress, ey = y + (y2 - y) * progress;
                canvas.drawLine(x, y, ex, ey, halo);
                canvas.drawLine(x, y, ex, ey, stroke);
                if (progress > 0.82f) arrowHead(canvas, x, y, ex, ey, halo, stroke, line);
                label(canvas, overlay.text, x, y, color, alpha, false);
                break;
            }
            case "ring": {
                float radius = Math.max(Ui.dp(getContext(), 14), Math.min((float) Math.hypot(x2 - x, y2 - y),
                        0.5f * Math.min(w, h))) * scale;
                canvas.drawCircle(x, y, radius, halo);
                canvas.drawCircle(x, y, radius, stroke);
                label(canvas, overlay.text, x, y + radius + Ui.dp(getContext(), 18), color, alpha, false);
                break;
            }
            case "box": {
                rect.set(Math.min(x, x2), Math.min(y, y2), Math.max(x, x2), Math.max(y, y2));
                scaleRect(rect, scale);
                float r = Ui.dp(getContext(), 8);
                if (progress < 1f) {
                    path.reset();
                    path.addRoundRect(rect, r, r, Path.Direction.CW);
                    measure.setPath(path, false);
                    part.reset();
                    measure.getSegment(0, measure.getLength() * progress, part, true);
                    canvas.drawPath(part, halo);
                    canvas.drawPath(part, stroke);
                } else {
                    canvas.drawRoundRect(rect, r, r, halo);
                    canvas.drawRoundRect(rect, r, r, stroke);
                }
                label(canvas, overlay.text, rect.centerX(), rect.top - Ui.dp(getContext(), 18), color, alpha, false);
                break;
            }
            case "spotlight": {
                rect.set(Math.min(x, x2), Math.min(y, y2), Math.max(x, x2), Math.max(y, y2));
                if (rect.width() < 4 || rect.height() < 4) {
                    float r = 0.12f * Math.min(w, h);
                    rect.set(x - r, y - r, x + r, y + r);
                }
                scaleRect(rect, scale);
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                path.addRect(0, 0, w, h, Path.Direction.CW);
                path.addOval(rect, Path.Direction.CW);
                fill.setColor(withAlpha(Color.BLACK, 0.58f * alpha));
                canvas.drawPath(path, fill);
                stroke.setColor(withAlpha(color == Color.BLACK ? Ui.MINT : color, alpha));
                canvas.drawOval(rect, stroke);
                label(canvas, overlay.text, rect.centerX(), rect.bottom + Ui.dp(getContext(), 18), Ui.MINT, alpha, false);
                break;
            }
            case "label":
            case "note": {
                canvas.save();
                canvas.scale(scale, scale, x, y);
                label(canvas, overlay.text, x, y, color, alpha, overlay.type.equals("note"));
                canvas.restore();
                break;
            }
            case "image": {
                Bitmap bitmap = bitmaps.get(overlay.asset);
                if (bitmap == null) break;
                rect.set(Math.min(x, x2), Math.min(y, y2), Math.max(x, x2), Math.max(y, y2));
                scaleRect(rect, scale);
                image.setAlpha(Math.round(255 * alpha));
                canvas.drawBitmap(bitmap, null, rect, image);
                break;
            }
            default:
                break;
        }
    }

    private void arrowHead(Canvas canvas, float x, float y, float ex, float ey, Paint halo, Paint paint, float line) {
        double angle = Math.atan2(ey - y, ex - x);
        float size = line * 4.2f;
        path.reset();
        path.moveTo(ex, ey);
        path.lineTo((float) (ex - size * Math.cos(angle - 0.45)), (float) (ey - size * Math.sin(angle - 0.45)));
        path.moveTo(ex, ey);
        path.lineTo((float) (ex - size * Math.cos(angle + 0.45)), (float) (ey - size * Math.sin(angle + 0.45)));
        canvas.drawPath(path, halo);
        canvas.drawPath(path, paint);
    }

    /** A rounded text bubble centred on (cx, cy), kept inside the video. */
    private void label(Canvas canvas, String value, float cx, float cy, int accent, float alpha, boolean note) {
        if (value == null || value.isEmpty()) return;
        int maxWidth = Math.max(Ui.dp(getContext(), 80), Math.round(getWidth() * 0.6f));
        text.setColor(withAlpha(Color.WHITE, alpha));
        text.setFakeBoldText(!note);
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), text, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(6)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        float lw = 0;
        for (int i = 0; i < layout.getLineCount(); i++) lw = Math.max(lw, layout.getLineWidth(i));
        float pad = Ui.dp(getContext(), 7);
        float bw = lw + pad * 2, bh = layout.getHeight() + pad * 2;
        float leftX = clamp(cx - bw / 2, 2, getWidth() - bw - 2);
        float topY = clamp(cy - bh / 2, 2, getHeight() - bh - 2);
        rect.set(leftX, topY, leftX + bw, topY + bh);
        fill.setColor(withAlpha(note ? Color.rgb(46, 36, 12) : Color.rgb(8, 20, 24), 0.82f * alpha));
        float r = Ui.dp(getContext(), 9);
        canvas.drawRoundRect(rect, r, r, fill);
        border.setColor(withAlpha(note ? Ui.AMBER : accent, alpha));
        canvas.drawRoundRect(rect, r, r, border);
        canvas.save();
        canvas.translate(leftX + pad + (lw - layout.getWidth()) / 2f, topY + pad);
        layout.draw(canvas);
        canvas.restore();
    }

    private static void scaleRect(RectF rect, float scale) {
        if (scale == 1f) return;
        float cx = rect.centerX(), cy = rect.centerY();
        float hw = rect.width() * scale / 2, hh = rect.height() * scale / 2;
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private static float backOut(float t) {
        float s = 1.70158f;
        t -= 1;
        return 0.55f + 0.45f * (t * t * ((s + 1) * t + s) + 1);
    }

    private static float clamp(float value, float min, float max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
