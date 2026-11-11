package com.muse.monetnotes;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 侧边快速滚动条：右侧悬浮一个滑块，按住上下拖动即可按比例快速滚动，
 * 拖动时旁边显示百分比气泡。内容不够一屏时自动隐藏。
 */
public class FastScroll {

    public static void attach(Context ctx, FrameLayout parent, ScrollView sv, Palette pal, int bottomInsetDp) {
        final int thumbW = Ui.dp(ctx, 22);
        final int thumbH = Ui.dp(ctx, 68);
        final int inset = Ui.dp(ctx, bottomInsetDp);

        final View thumb = new View(ctx);
        int thumbColor = (pal.primary & 0x00FFFFFF) | 0xB3000000;
        thumb.setBackground(Ui.round(thumbColor, Ui.dp(ctx, 11)));
        thumb.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(thumbW, thumbH, Gravity.END | Gravity.TOP);
        tlp.setMargins(0, 0, Ui.dp(ctx, 3), 0);
        parent.addView(thumb, tlp);

        final TextView bubble = Ui.text(ctx, "0%", 12, pal.textPrimary, true);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(ctx, 12)));
        bubble.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4));
        bubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        blp.setMargins(0, 0, Ui.dp(ctx, 32), 0);
        parent.addView(bubble, blp);

        final boolean[] dragging = {false};

        Runnable update = () -> {
            if (dragging[0]) return;
            int max = maxScroll(sv);
            if (max <= 0) {
                thumb.setVisibility(View.GONE);
                return;
            }
            thumb.setVisibility(View.VISIBLE);
            float frac = sv.getScrollY() / (float) max;
            float track = trackRange(ctx, parent, sv, thumbH, inset);
            thumb.setTranslationY(offsetTop(sv, parent) + frac * track);
        };
        sv.getViewTreeObserver().addOnScrollChangedListener(update::run);
        sv.getViewTreeObserver().addOnGlobalLayoutListener(update::run);

        thumb.setOnTouchListener((v, ev) -> {
            int max = maxScroll(sv);
            if (max <= 0) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    dragging[0] = true;
                    bubble.setVisibility(View.VISIBLE);
                    thumb.setScaleX(1.35f);
                    int[] loc = new int[2];
                    parent.getLocationInWindow(loc);
                    float localY = ev.getRawY() - loc[1];
                    float top0 = offsetTop(sv, parent);
                    float track = trackRange(ctx, parent, sv, thumbH, inset);
                    float frac = track <= 0 ? 0 : (localY - top0 - thumbH / 2f) / track;
                    if (frac < 0) frac = 0;
                    if (frac > 1) frac = 1;
                    sv.scrollTo(0, (int) (frac * max));
                    thumb.setTranslationY(top0 + frac * track);
                    bubble.setTranslationY(top0 + frac * track);
                    bubble.setText(Math.round(frac * 100) + "%");
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    dragging[0] = false;
                    bubble.setVisibility(View.GONE);
                    thumb.setScaleX(1f);
                    update.run();
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /** 一键回到顶部按钮：滚动超过一屏后淡入出现，点按平滑滚回顶部。
     *  gravityEnd=true 放右下角，false 放左下角；bottomDp 为距底边距。 */
    public static void attachBackToTop(Context ctx, FrameLayout parent, ScrollView sv, Palette pal,
                                       boolean gravityEnd, int bottomDp, int sideDp) {
        final TextView btn = Ui.text(ctx, "↑", 20, pal.onPrimary, true);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(pal.primary);
        btn.setBackground(circle);
        btn.setElevation(Ui.dp(ctx, 6));
        btn.setAlpha(0f);
        btn.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48),
                Gravity.BOTTOM | (gravityEnd ? Gravity.END : Gravity.START));
        lp.setMargins(Ui.dp(ctx, sideDp), 0, Ui.dp(ctx, sideDp), Ui.dp(ctx, bottomDp));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> sv.smoothScrollTo(0, 0));
        parent.addView(btn);

        final boolean[] shown = {false};
        sv.getViewTreeObserver().addOnScrollChangedListener(() -> {
            boolean shouldShow = sv.getScrollY() > sv.getHeight();
            if (shouldShow == shown[0]) return;
            shown[0] = shouldShow;
            if (shouldShow) {
                btn.setVisibility(View.VISIBLE);
                btn.animate().alpha(1f).setDuration(180).start();
            } else {
                btn.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> btn.setVisibility(View.GONE)).start();
            }
        });
    }

    private static int maxScroll(ScrollView sv) {
        if (sv.getChildCount() == 0) return 0;
        return Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
    }

    private static float trackRange(Context ctx, FrameLayout parent, ScrollView sv, int thumbH, int bottomInsetPx) {
        return Math.max(0, sv.getHeight() - thumbH - bottomInsetPx - Ui.dp(ctx, 8));
    }

    /** sv 相对于 overlay parent 的顶端偏移。 */
    private static int offsetTop(View sv, View ancestor) {
        int top = 0;
        View v = sv;
        while (v != null && v != ancestor) {
            top += v.getTop();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return top;
    }
}
