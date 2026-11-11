package com.muse.monetnotes;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class Ui {

    /** 弹性按压反馈：按下时轻微缩小，松手/取消时以弹簧过冲回到原样（不消费事件，不影响点击/长按）。 */
    public static void elasticPress(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    animateDeform(view, 1f, 170, new android.view.animation.DecelerateInterpolator());
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    animateDeform(view, 0f, 560, new android.view.animation.OvershootInterpolator(2.6f));
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private static final java.util.WeakHashMap<View, android.animation.ValueAnimator> deformAnims =
            new java.util.WeakHashMap<>();

    /** 液态按压形变（Qwen 演示同款配方）：按下横向延展 +4.5%、纵向收缩 −5.5%，
     *  玻璃的圆角/透镜/染色随形变同步（见 GlassDrawable.pressDeform），
     *  松手用过冲插值模拟低阻尼弹簧（阻尼比 0.40）的回摆。 */
    private static void animateDeform(final View view, float target, int durationMs,
                                      android.view.animation.Interpolator interp) {
        android.animation.ValueAnimator prev;
        synchronized (deformAnims) { prev = deformAnims.get(view); }
        if (prev != null) prev.cancel();
        float start = (view.getScaleX() - 1f) / 0.045f;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(start, target);
        va.setDuration(durationMs);
        va.setInterpolator(interp);
        va.addUpdateListener(a -> {
            float d = (float) a.getAnimatedValue();
            view.setScaleX(1f + 0.045f * d);
            view.setScaleY(1f - 0.055f * d);
            android.graphics.drawable.Drawable bg = view.getBackground();
            if (bg instanceof GlassDrawable) ((GlassDrawable) bg).setPressDeform(d);
        });
        synchronized (deformAnims) { deformAnims.put(view, va); }
        va.start();
    }

    /** 液态玻璃开关（由 Palette.of 同步），开启时半透明表面渲染为带高光与亮边的玻璃渐变。 */
    public static boolean GLASS = false;
    public static boolean GLASS_DARK = false;

    /** 把系统弹窗染上当前主题：圆角玻璃背景、主题色标题/按钮/列表，玻璃模式下背景真模糊。 */
    public static void styleDialog(android.content.Context ctx, android.app.AlertDialog dlg) {
        if (dlg == null) return;
        Palette pal = Palette.of(ctx);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            int bg = pal.glass ? Palette.withAlpha(pal.surface, 0xE8) : pal.surface;
            if (pal.glass && android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();
                    lp.flags |= android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                    lp.setBlurBehindRadius(dp(ctx, 28));
                    w.setAttributes(lp);
                } catch (Exception ignored) {}
            }
            w.setBackgroundDrawable(round(bg, dp(ctx, 28)));
        }
        android.widget.TextView title = dlg.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(pal.textPrimary);
        android.widget.TextView msg = dlg.findViewById(android.R.id.message);
        if (msg != null) msg.setTextColor(pal.textSecondary);
        int[] which = {android.content.DialogInterface.BUTTON_POSITIVE,
                android.content.DialogInterface.BUTTON_NEGATIVE,
                android.content.DialogInterface.BUTTON_NEUTRAL};
        for (int btn : which) {
            android.widget.Button b = dlg.getButton(btn);
            if (b != null) b.setTextColor(btn == android.content.DialogInterface.BUTTON_NEGATIVE
                    ? pal.textSecondary : pal.primary);
        }
        android.widget.ListView lv = dlg.getListView();
        if (lv != null) {
            lv.post(() -> {
                for (int i = 0; i < lv.getChildCount(); i++) {
                    android.view.View child = lv.getChildAt(i);
                    if (child instanceof android.widget.TextView) {
                        ((android.widget.TextView) child).setTextColor(pal.textPrimary);
                    } else if (child instanceof android.view.ViewGroup) {
                        android.view.ViewGroup g = (android.view.ViewGroup) child;
                        for (int j = 0; j < g.getChildCount(); j++) {
                            if (g.getChildAt(j) instanceof android.widget.TextView)
                                ((android.widget.TextView) g.getChildAt(j)).setTextColor(pal.textPrimary);
                        }
                    }
                }
            });
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int brighten(int color, float f) {
        int r = Math.min(255, (int) (android.graphics.Color.red(color) + 255 * f));
        int g = Math.min(255, (int) (android.graphics.Color.green(color) + 255 * f));
        int b = Math.min(255, (int) (android.graphics.Color.blue(color) + 255 * f));
        return android.graphics.Color.argb(android.graphics.Color.alpha(color), r, g, b);
    }

    /** 真·液态玻璃：背景模糊切片 + 边缘折射 + 高光（见 GlassDrawable）。 */
    private static GlassDrawable glassDrawable(int color, float radiusPx) {
        return new GlassDrawable(color, radiusPx);
    }

    public static int dp(Context ctx, float dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static android.graphics.drawable.Drawable round(int color, float radiusPx) {
        // 玻璃开启且颜色本身半透明时，渲染为真液态玻璃
        if (GLASS && android.graphics.Color.alpha(color) < 0xFF) return glassDrawable(color, radiusPx);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** 浮层玻璃：采样全场景（PixelCopy 成品帧），透出其下方真实界面，用于多选栏/撤销栏。 */
    public static android.graphics.drawable.Drawable roundScene(int color, float radiusPx) {
        if (GLASS && android.graphics.Color.alpha(color) < 0xFF) {
            GlassDrawable g = glassDrawable(color, radiusPx);
            g.sceneSource = true;
            g.glassStyle = GlassDrawable.STYLE_PILL;
            return g;
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static android.graphics.drawable.Drawable roundStroke(int color, float radiusPx, int strokePx, int strokeColor) {
        // 玻璃化只接管细描边表面；粗描边（如选中高亮）保留原样
        if (GLASS && android.graphics.Color.alpha(color) < 0xFF && strokePx <= 6) {
            return glassDrawable(color, radiusPx);
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    public static TextView text(Context ctx, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b, Context ctx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b));
        return p;
    }

    public static void animateIn(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(24f);
        v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(index, 8) * 35L)
                .setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
}
