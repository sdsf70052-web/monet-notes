package com.muse.monetnotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/** 动态背景：极光 / 气泡 / 星空 / 波浪四种动画效果，颜色取自当前主题。 */
public class AnimBgView extends View {
    public static final int FX_AURORA = 1;
    public static final int FX_BUBBLES = 2;
    public static final int FX_STARS = 3;
    public static final int FX_WAVES = 4;
    public static final int FX_FLUID = 5;

    private final int effect;
    private final Palette pal;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // 极光/流体的低分辨率缓存（见 drawCached）
    private Bitmap fxCache;
    private Canvas fxCanvas;
    private long fxCachePhase = -1;
    private final android.graphics.Rect fxDst = new android.graphics.Rect();

    // 气泡：x(0-1), 半径dp, 速度, 相位
    private final float[][] bubbles = new float[14][4];
    // 星星：x(0-1), y(0-1), 半径dp, 相位
    private final float[][] stars = new float[80][4];

    /**
     * 全局唯一的动画驱动：整个应用只跑一条 Choreographer 回调链，
     * 统一以约 30fps 刷新所有已注册且窗口可见的动态背景。
     * （此前每个视图各自驱动，页面重建/叠加时多条链互相抢帧会导致疯狂闪烁。）
     */
    private static class Ticker {
        static final java.util.Set<AnimBgView> VIEWS =
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        static boolean running = false;
        static long lastTick = 0;
        static int tickCount = 0;
        static final Choreographer.FrameCallback CB = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (!running) return;
                long now = SystemClock.uptimeMillis();
                // 效果强度「流畅」档：动画驱动降到约 15fps 省电省 GPU；极致/均衡保持 30fps
                int gateMs = 33;
                for (AnimBgView any : VIEWS) {
                    gateMs = Palette.fxAnimGateMs(any.getContext());
                    break;
                }
                if (now - lastTick >= gateMs) {
                    lastTick = now;
                    tickCount++;
                    for (AnimBgView v : new java.util.ArrayList<>(VIEWS)) {
                        if (v.getWindowVisibility() == VISIBLE && v.isShown() && v.hasWindowFocus()) {
                            // 液态玻璃：每帧把动态背景实时绘入共享小图，并驱动所有玻璃面重绘
                            // （只更新纹理不重绘卡片的话，卡片里的画面会定格在旧帧上）
                            if (BgHelper.glassWanted) {
                                BgHelper.publishAnimFrame(v, v.effect, Palette.withAlpha(v.pal.background, 0x26));
                                // 场景已随动画帧变化（浮层栏可见时 PixelCopy 循环会取新帧）
                                BgHelper.markSceneDirty();
                                GlassDrawable.refreshLive();
                            }
                            v.invalidate();
                        }
                    }
                }
                if (VIEWS.isEmpty()) { running = false; return; }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        static synchronized void register(AnimBgView v) {
            VIEWS.add(v);
            if (!running) {
                running = true;
                lastTick = 0;
                Choreographer.getInstance().postFrameCallback(CB);
            }
        }

        static synchronized void unregister(AnimBgView v) {
            VIEWS.remove(v);
        }
    }

    public AnimBgView(Context ctx, int effect, Palette pal) {
        super(ctx);
        this.effect = effect;
        this.pal = pal;
        Random rnd = new Random(20261111L);
        for (int i = 0; i < bubbles.length; i++) {
            bubbles[i][0] = rnd.nextFloat();
            bubbles[i][1] = 6 + rnd.nextFloat() * 22;
            bubbles[i][2] = 0.008f + rnd.nextFloat() * 0.02f;
            bubbles[i][3] = rnd.nextFloat() * 6.28f;
        }
        for (int i = 0; i < stars.length; i++) {
            stars[i][0] = rnd.nextFloat();
            stars[i][1] = rnd.nextFloat();
            stars[i][2] = 0.8f + rnd.nextFloat() * 1.8f;
            stars[i][3] = rnd.nextFloat() * 6.28f;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ticker.register(this);
    }

    @Override protected void onDetachedFromWindow() {
        Ticker.unregister(this);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long t = SystemClock.uptimeMillis();
        float density = getResources().getDisplayMetrics().density;
        if (effect == FX_AURORA || effect == FX_FLUID) { drawCached(canvas, w, h, t); return; }
        canvas.drawColor(pal.background);
        switch (effect) {
            case FX_AURORA: drawAurora(canvas, w, h, t); break;
            case FX_BUBBLES: drawBubbles(canvas, w, h, t, density); break;
            case FX_STARS: drawStars(canvas, w, h, t, density); break;
            case FX_WAVES: drawWaves(canvas, w, h, t); break;
            case FX_FLUID: drawFluid(canvas, w, h, t); break;
        }
    }

    /**
     * 极光 / 流体专用路径（v1.42 闪烁修复）：两者都是全屏多层径向渐变（3~7 层近全屏
     * 覆盖），每帧在全分辨率下重绘是所有动态背景里最重的，帧耗时忽长忽短，
     * 呈现出来就是忽快忽慢的闪烁；气泡/星空/波浪都是小面积元素，没有这个问题。
     * 两者画面极其柔和，先以 1/3 分辨率绘入缓存小图、再双线性拉伸上屏，肉眼无差别，
     * 单帧成本降约一个数量级，帧率随之稳定。相位再按 33ms 量化：高帧率档下
     * 相邻帧共用同一幅缓存，不重复生成渐变。
     */
    private void drawCached(Canvas c, int w, int h, long t) {
        int bw = Math.max(48, w / 3), bh = Math.max(48, h / 3);
        if (fxCache == null || fxCache.getWidth() != bw || fxCache.getHeight() != bh) {
            fxCache = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            fxCanvas = new Canvas(fxCache);
            fxCachePhase = -1;
        }
        long phase = t - (t % 33);
        if (phase != fxCachePhase) {
            fxCachePhase = phase;
            fxCanvas.save();
            fxCanvas.scale(bw / (float) w, bh / (float) h);
            fxCanvas.drawColor(pal.background);
            if (effect == FX_AURORA) drawAurora(fxCanvas, w, h, phase);
            else drawFluid(fxCanvas, w, h, phase);
            fxCanvas.restore();
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setFilterBitmap(true);
        fxDst.set(0, 0, w, h);
        c.drawBitmap(fxCache, null, fxDst, paint);
        paint.setFilterBitmap(false);
    }

    private void drawAurora(Canvas c, int w, int h, long t) {
        int[] colors = {pal.primary, Palette.blend(pal.primary, 0xFF7E57C2, 0.45f), pal.primaryContainer};
        for (int i = 0; i < colors.length; i++) {
            double a = t * 0.00006 * (i + 1) + i * 2.1;
            float cx = (float) (w * (0.28 + 0.24 * Math.sin(a)));
            float cy = (float) (h * (0.30 + 0.20 * Math.cos(a * 0.8 + i)));
            float r = w * 0.85f;
            RadialGradient rg = new RadialGradient(cx, cy, r,
                    Palette.withAlpha(colors[i], 0x59), Palette.withAlpha(colors[i], 0),
                    Shader.TileMode.CLAMP);
            paint.setShader(rg);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r, paint);
        }
        paint.setShader(null);
    }

    /** 流体（Qwen 演示的壁纸配方）：深蓝斜渐变底 + 六团饱和色径向渐变，
     *  位置正弦漂移、半径呼吸。颜色表与运动系数照其 blobs 定义。 */
    private void drawFluid(Canvas c, int w, int h, long t) {
        float ts = t / 1000f;
        android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                0, 0, w, h, new int[]{0xFF071E3D, 0xFF0B2A5B, 0xFF1A2E6E},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(lg);
        paint.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, w, h, paint);
        int[] colors = {0xFF00E5FF, 0xFF2979FF, 0xFFD500F9, 0xFFFF9100, 0xFF3D5AFE, 0xFF00E5A5};
        float[][] blobs = { // {x, y, r(相对短边), 相位}
                {0.15f, 0.12f, 0.75f, 0.0f}, {0.45f, 0.30f, 0.85f, 1.3f},
                {0.88f, 0.38f, 0.80f, 2.1f}, {0.85f, 0.80f, 0.75f, 3.0f},
                {0.25f, 0.75f, 0.80f, 4.1f}, {0.05f, 0.45f, 0.60f, 5.2f}};
        float minDim = Math.min(w, h);
        for (int i = 0; i < blobs.length; i++) {
            float[] b = blobs[i];
            float px = (b[0] + 0.10f * (float) Math.sin(ts * 0.35f + b[3])) * w;
            float py = (b[1] + 0.10f * (float) Math.cos(ts * 0.28f + b[3] * 1.7f)) * h;
            float r = b[2] * minDim * (0.75f + 0.10f * (float) Math.sin(ts * 0.5f + b[3]));
            RadialGradient rg = new RadialGradient(px, py, Math.max(1f, r),
                    Palette.withAlpha(colors[i], 0xD9), Palette.withAlpha(colors[i], 0),
                    Shader.TileMode.CLAMP);
            paint.setShader(rg);
            c.drawCircle(px, py, r, paint);
        }
        paint.setShader(null);
    }

    private void drawBubbles(Canvas c, int w, int h, long t, float density) {
        for (float[] b : bubbles) {
            float speed = b[2];
            float prog = (float) (((t * speed * 0.001) + b[3]) % 1.0);
            float y = h * (1.05f - prog * 1.15f);
            float x = b[0] * w + (float) Math.sin(t * 0.001 + b[3] * 3) * 14 * density;
            float r = b[1] * density;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Palette.withAlpha(pal.dark ? 0xFFFFFFFF : pal.primary, 0x1A));
            c.drawCircle(x, y, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * density);
            paint.setColor(Palette.withAlpha(pal.dark ? 0xFFFFFFFF : pal.primary, 0x38));
            c.drawCircle(x, y, r, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStars(Canvas c, int w, int h, long t, float density) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        int starColor = pal.dark ? 0xFFE9E4FF : pal.primary;
        for (float[] st : stars) {
            float tw = 0.35f + 0.30f * (float) Math.sin(t * 0.002 + st[3]);
            paint.setColor(Palette.withAlpha(starColor, (int) (255 * Math.max(0.08f, tw))));
            c.drawCircle(st[0] * w, st[1] * h, st[2] * density, paint);
        }
        // 两颗带光晕的大星
        for (int i = 0; i < 2; i++) {
            float cx = w * (i == 0 ? 0.22f : 0.78f);
            float cy = h * (i == 0 ? 0.18f : 0.42f);
            RadialGradient rg = new RadialGradient(cx, cy, 90 * density,
                    Palette.withAlpha(pal.primary, 0x40), Palette.withAlpha(pal.primary, 0), Shader.TileMode.CLAMP);
            paint.setShader(rg);
            c.drawCircle(cx, cy, 90 * density, paint);
        }
        paint.setShader(null);
    }

    private void drawWaves(Canvas c, int w, int h, long t) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        int[] cols = {pal.primary, pal.primaryContainer, Palette.blend(pal.primary, 0xFF64B5F6, 0.4f)};
        for (int i = 0; i < 3; i++) {
            float base = h * (0.72f + i * 0.09f);
            float amp = (10 + i * 7) * getResources().getDisplayMetrics().density;
            double phase = t * 0.0011 * (i + 1) + i * 1.7;
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x <= w; x += 24) {
                float y = base + (float) Math.sin(x * 0.008 + phase) * amp;
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            paint.setColor(Palette.withAlpha(cols[i], 0x2E + i * 0x10));
            c.drawPath(path, paint);
        }
    }
}
