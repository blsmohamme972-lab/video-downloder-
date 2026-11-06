package app.streamsave;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, framework-only visual components shared by the download screens. */
final class Ui {
    static final int BG = Color.rgb(11, 18, 23);
    static final int SURFACE = Color.rgb(20, 30, 37);
    static final int SURFACE_HIGH = Color.rgb(27, 39, 47);
    static final int BORDER = Color.rgb(43, 58, 67);
    static final int TEXT = Color.rgb(239, 246, 247);
    static final int MUTED = Color.rgb(161, 179, 188);
    static final int QUIET = Color.rgb(116, 140, 152);
    static final int MINT = Color.rgb(100, 232, 191);
    static final int MINT_DARK = Color.rgb(22, 55, 47);
    static final int INK = Color.rgb(8, 37, 29);
    static final int AMBER = Color.rgb(255, 206, 121);
    static final int RED = Color.rgb(255, 157, 158);

    private Ui() { }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout vertical(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static TextView text(Context c, String text, float size, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(text == null ? "" : text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(c, 3), 1f);
        return view;
    }

    static TextView eyebrow(Context c, String text) {
        TextView view = text(c, text, 11f, MUTED, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    static GradientDrawable shape(Context c, int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(c, 1), stroke);
        return drawable;
    }

    static Drawable ripple(Context c, int color, float radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x287BE9CC),
                shape(c, color, radius, stroke), shape(c, Color.WHITE, radius, Color.TRANSPARENT));
    }

    static Button button(Context c, String title, boolean primary) {
        Button button = new Button(c);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(14f);
        button.setTextColor(primary ? INK : TEXT);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(c, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        button.setBackground(ripple(c, primary ? MINT : SURFACE_HIGH, 14,
                primary ? Color.TRANSPARENT : BORDER));
        button.setStateListAnimator(null);
        return button;
    }

    static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    static View spacer(Context c, float height) {
        View view = new View(c);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height)));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static IconView iconButton(Context c, String icon, String description) {
        IconView view = new IconView(c, icon, MUTED);
        view.setContentDescription(description);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(ripple(c, Color.TRANSPARENT, 14, Color.TRANSPARENT));
        return view;
    }

    static TextView chip(Context c, String title, int foreground, int background) {
        TextView view = text(c, title, 11, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        view.setBackground(shape(c, background, 100, Color.TRANSPARENT));
        return view;
    }

    static void buttonIcon(Button button, String name, int color) {
        IconDrawable drawable = new IconDrawable(name, color);
        int size = dp(button.getContext(), 18);
        drawable.setBounds(0, 0, size, size);
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(button.getContext(), 8));
    }

    /** Centers the content and keeps controls readable on tablets and landscape screens. */
    static final class MaxWidthFrame extends FrameLayout {
        MaxWidthFrame(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec);
            int width = Math.min(available, dp(getContext(), 720));
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.getMode(widthSpec)), heightSpec);
        }
    }

    @SuppressLint("ViewConstructor") // Constructed in code with an explicit vector name and color; never inflated.
    static final class IconView extends View {
        private final IconDrawable drawable;
        private int iconSize = 22;

        IconView(Context c, String name, int color) {
            super(c);
            drawable = new IconDrawable(name, color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void color(int color) { drawable.setColor(color); invalidate(); }
        void size(int dp) { iconSize = dp; invalidate(); }

        @Override public void setContentDescription(CharSequence description) {
            super.setContentDescription(description);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int size = Math.min(dp(getContext(), iconSize), Math.min(getWidth(), getHeight()));
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            drawable.setBounds(x, y, x + size, y + size);
            drawable.draw(canvas);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (isClickable()) info.setClassName(Button.class.getName());
        }
    }

    /** Vector strokes avoid emoji/font differences across Android versions. */
    static final class IconDrawable extends Drawable {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int color;
        private int alpha = 255;

        IconDrawable(String name, int color) { this.name = name; this.color = color; }
        void setColor(int value) { color = value; invalidateSelf(); }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            switch (name) {
                case "download":
                    canvas.drawLine(12, 3, 12, 15, paint);
                    line(canvas, 7, 10, 12, 15, 17, 10);
                    path.moveTo(4, 16); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 16);
                    canvas.drawPath(path, paint); break;
                case "play":
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
                    canvas.drawPath(path, paint); break;
                case "pause":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6, 5, 10, 19, 1, 1, paint);
                    canvas.drawRoundRect(14, 5, 18, 19, 1, 1, paint); break;
                case "check":
                    line(canvas, 5, 12, 10, 17, 19, 7); break;
                case "plus":
                    canvas.drawLine(12, 5, 12, 19, paint);
                    canvas.drawLine(5, 12, 19, 12, paint); break;
                case "link":
                    path.moveTo(10, 7); path.lineTo(12, 5);
                    path.cubicTo(17, 0, 24, 7, 19, 12); path.lineTo(17, 14);
                    canvas.drawPath(path, paint); path.reset();
                    path.moveTo(14, 17); path.lineTo(12, 19);
                    path.cubicTo(7, 24, 0, 17, 5, 12); path.lineTo(7, 10);
                    canvas.drawPath(path, paint); canvas.drawLine(9, 15, 15, 9, paint); break;
                case "paste":
                    canvas.drawRoundRect(5, 5, 19, 21, 2, 2, paint);
                    paint.setStyle(Paint.Style.FILL); paint.setColor(SURFACE_HIGH);
                    canvas.drawRoundRect(8, 2, 16, 7, 1.5f, 1.5f, paint);
                    paint.setStyle(Paint.Style.STROKE); paint.setColor(color);
                    canvas.drawRoundRect(8, 2, 16, 7, 1.5f, 1.5f, paint);
                    canvas.drawLine(9, 12, 15, 12, paint); canvas.drawLine(9, 16, 14, 16, paint); break;
                case "more":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12, 5, 1.6f, paint); canvas.drawCircle(12, 12, 1.6f, paint);
                    canvas.drawCircle(12, 19, 1.6f, paint); break;
                case "settings":
                    canvas.drawCircle(12, 12, 3, paint);
                    for (int i = 0; i < 8; i++) {
                        double angle = i * Math.PI / 4;
                        canvas.drawLine(12 + 8 * (float) Math.cos(angle), 12 + 8 * (float) Math.sin(angle),
                                12 + 10 * (float) Math.cos(angle), 12 + 10 * (float) Math.sin(angle), paint);
                    }
                    canvas.drawCircle(12, 12, 7.5f, paint); break;
                case "arrow":
                    canvas.drawLine(4, 12, 20, 12, paint); line(canvas, 14, 6, 20, 12, 14, 18); break;
                case "close":
                    canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint); break;
                case "folder":
                    path.moveTo(3, 7); path.lineTo(3, 19); path.quadTo(3, 21, 5, 21);
                    path.lineTo(19, 21); path.quadTo(21, 21, 21, 19); path.lineTo(21, 8);
                    path.quadTo(21, 6, 19, 6); path.lineTo(11, 6); path.lineTo(9, 3);
                    path.lineTo(5, 3); path.quadTo(3, 3, 3, 5); path.lineTo(3, 7); path.close();
                    canvas.drawPath(path, paint); break;
                case "music":
                    path.moveTo(10, 17); path.lineTo(10, 5); path.lineTo(20, 3); path.lineTo(20, 15);
                    canvas.drawPath(path, paint); canvas.drawLine(10, 9, 20, 7, paint);
                    canvas.drawOval(4, 15, 10, 21, paint); canvas.drawOval(14, 13, 20, 19, paint); break;
                case "info":
                    canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 11, 12, 17, paint);
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 7, 1, paint); break;
                case "refresh":
                    canvas.drawArc(new RectF(4, 4, 20, 20), 40, 290, false, paint);
                    line(canvas, 15, 3, 20, 4, 20, 9); break;
                case "bolt":
                    path.moveTo(14, 2); path.lineTo(5, 14); path.lineTo(11, 14);
                    path.lineTo(10, 22); path.lineTo(19, 10); path.lineTo(13, 10); path.close();
                    canvas.drawPath(path, paint); break;
                case "share":
                    canvas.drawCircle(18, 5, 3, paint); canvas.drawCircle(6, 12, 3, paint);
                    canvas.drawCircle(18, 19, 3, paint); canvas.drawLine(8.7f, 10.5f, 15.3f, 6.5f, paint);
                    canvas.drawLine(8.7f, 13.5f, 15.3f, 17.5f, paint); break;
                default:
                    canvas.drawCircle(12, 12, 8, paint);
            }
            canvas.restoreToCount(save);
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2, float x3, float y3) {
            path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3);
            canvas.drawPath(path, paint); path.reset();
        }

        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class ProgressTrack extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private float value;
        private float phase = 0.5f;
        private int color = MINT;
        private boolean indeterminate;
        private ValueAnimator animator;

        ProgressTrack(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void update(float progress, int fill, boolean busy) {
            value = Float.isNaN(progress) || Float.isInfinite(progress) ? 0 : Math.max(0, Math.min(100, progress));
            color = fill;
            if (indeterminate != busy) {
                indeterminate = busy;
                updateAnimation();
            }
            invalidate();
        }

        private void updateAnimation() {
            boolean shouldRun = indeterminate && isAttachedToWindow() && getVisibility() == VISIBLE
                    && getWindowVisibility() == VISIBLE && isShown() && ValueAnimator.areAnimatorsEnabled();
            if (!shouldRun && animator != null) { animator.cancel(); animator = null; }
            if (shouldRun && animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(1400);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(animation -> { phase = (float) animation.getAnimatedValue(); invalidate(); });
                animator.start();
            }
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); }
        @Override protected void onDetachedFromWindow() {
            if (animator != null) { animator.cancel(); animator = null; }
            super.onDetachedFromWindow();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            updateAnimation();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateAnimation();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = getHeight() / 2f;
            bounds.set(0, 0, getWidth(), getHeight());
            paint.setColor(BORDER);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.setColor(color);
            if (indeterminate) {
                float width = getWidth() * 0.3f;
                float left = (getWidth() + width) * phase - width;
                bounds.set(Math.max(0, left), 0, Math.min(getWidth(), left + width), getHeight());
            } else bounds.set(0, 0, getWidth() * value / 100f, getHeight());
            if (bounds.width() > 0) canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }
}
