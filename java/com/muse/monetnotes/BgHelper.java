package com.muse.monetnotes;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 全屏背景：自定义图片（可高斯模糊）或液态玻璃渐变，垫在界面最底层。 */
public class BgHelper {
    private static Bitmap cached;
    private static long cachedStamp = -1;
    private static int cachedLevel = -1;

    // ---- 液态玻璃的背景切片来源（GlassDrawable 采样） ----
    public static volatile Bitmap backdropBmp;
    public static volatile boolean backdropCrop;
    public static volatile boolean glassWanted;
    private static java.lang.ref.WeakReference<View> backdropRef;
    private static Bitmap rawCache;
    private static long rawStamp = -1;
    private static Bitmap animSnap;
    private static int animSnapFx = -1;

    public static View backdropView() {
        return backdropRef == null ? null : backdropRef.get();
    }

    // ---- 全场景拷贝（浮层玻璃采样其下方的真实界面，含卡片等内容） ----
    // 方式：PixelCopy 向系统要主窗「已经合成好的成品帧」——全程不重绘视图树，
    // 因此没有手动录制那种泛白/闪烁副作用。浮层栏住在独立浮窗里，不会被拷进画面。
    public static volatile Bitmap sceneBmp;
    /** 场景的重雾版（两轮 1/5 降采样伪高斯）：供 pill 样式的雾层单次采样，免着色器逐像素卷积。 */
    public static volatile Bitmap sceneHazeBmp;
    /** 场景帧对应的窗口区域顶边（窗口像素）：只拷底部条带时 > 0，采样映射据此偏移。 */
    public static volatile int sceneOriginY;
    /** 当前场景帧的等效预模糊 sigma（随效果强度的采样档位变），供着色器磨砂补偿。 */
    public static volatile float sceneSigma;
    private static long sceneLastStartMs;
    private static boolean sceneDeferPosted;
    private static int scenePendingOriginY;
    private static Bitmap sceneHazeA;
    private static final Bitmap[] sceneHazeOuts = new Bitmap[2];
    private static int sceneHazeIdx;
    private static final android.graphics.Paint sceneBlurPaint =
            new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private static java.lang.ref.WeakReference<View> sceneRef;
    private static final Bitmap[] sceneBufs = new Bitmap[3];
    private static volatile int sceneBufIdx;
    private static java.lang.ref.WeakReference<android.app.Activity> sceneActRef;
    private static volatile boolean sceneLoopOn;
    private static boolean sceneCbPosted;
    private static volatile boolean sceneCopyBusy;
    private static volatile boolean sceneDirty = true;
    private static int sceneFailStreak;
    private static int sceneHazeGen;
    private static android.os.HandlerThread sceneThread;
    private static android.os.Handler sceneHandler; // 拷贝回调与雾图都在这条后台线程上跑，主线程零阻塞
    private static final android.os.Handler sceneMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable sceneRetry = () -> trySceneCopy();

    private static synchronized android.os.Handler sceneHandler() {
        if (sceneHandler == null) {
            sceneThread = new android.os.HandlerThread("monet-scene");
            sceneThread.start();
            sceneHandler = new android.os.Handler(sceneThread.getLooper());
        }
        return sceneHandler;
    }

    public static View sceneView() {
        return sceneRef == null ? null : sceneRef.get();
    }

    /** 场景内容变了（滚动/刷新/动态背景帧）：浮层栏可见期间，拷贝循环会据此取新帧。 */
    public static void markSceneDirty() {
        sceneDirty = true;
    }

    /** 浮层栏出现时启动：逐帧检查，脏了就拷一帧主窗成品图供浮层玻璃采样。 */
    public static void startSceneLoop(android.app.Activity act) {
        sceneActRef = new java.lang.ref.WeakReference<>(act);
        sceneDirty = true;
        if (sceneLoopOn) return;
        sceneLoopOn = true;
        postSceneFrame();
    }

    public static void stopSceneLoop() {
        sceneLoopOn = false;
    }

    private static final android.view.Choreographer.FrameCallback sceneFrameCb = frameTimeNanos -> {
        sceneCbPosted = false;
        if (!sceneLoopOn) return;
        trySceneCopy();
        postSceneFrame();
    };

    private static void postSceneFrame() {
        if (sceneCbPosted) return;
        sceneCbPosted = true;
        android.view.Choreographer.getInstance().postFrameCallback(sceneFrameCb);
    }

    private static void trySceneCopy() {
        if (sceneCopyBusy || !sceneDirty) return;
        android.app.Activity act = sceneActRef == null ? null : sceneActRef.get();
        if (act == null || act.isFinishing()) { sceneLoopOn = false; return; }
        android.view.Window win = act.getWindow();
        if (win == null) return;
        View decor = win.getDecorView();
        if (decor == null || !decor.isAttachedToWindow()
                || decor.getWidth() <= 0 || decor.getHeight() <= 0) return;
        int w = decor.getWidth(), h = decor.getHeight();
        // 采样分辨率随「效果强度」档位：极致 1:1 不压缩（默认）、均衡 1/2、流畅 1/3；
        // 仍只拷底部 55% 条带（两栏都在底部，裁区域不降清晰度）。
        int minGapMs = Palette.fxCopyMinGapMs(act); // 拷贝帧率上限（效果强度档位/自定义值解析）
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (minGapMs > 0 && nowMs - sceneLastStartMs < minGapMs) {
            if (!sceneDeferPosted) {
                sceneDeferPosted = true;
                final long waitMs = minGapMs - (nowMs - sceneLastStartMs);
                sceneMainHandler.postDelayed(() -> { sceneDeferPosted = false; trySceneCopy(); }, waitMs);
            }
            return; // sceneDirty 保持，延时到点再拷
        }
        int bandTop = android.os.Build.VERSION.SDK_INT >= 28 ? (int) (h * 0.45f) : 0;
        int div = Palette.fxSceneDiv(act);
        int bw = Math.max(48, w / div), bh = Math.max(48, (h - bandTop) / div);
        sceneSigma = div <= 1 ? 0f : Math.min(1.2f, 0.5f * div - 0.4f);
        scenePendingOriginY = bandTop;
        Bitmap back = sceneBufs[sceneBufIdx];
        if (back == null || back.getWidth() != bw || back.getHeight() != bh) {
            back = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            sceneBufs[sceneBufIdx] = back;
        }
        sceneCopyBusy = true;
        sceneDirty = false;
        sceneLastStartMs = android.os.SystemClock.uptimeMillis();
        final Bitmap dest = back;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                // Rect 重载：源区域自动缩放进目标小图
                android.view.PixelCopy.request(win, new android.graphics.Rect(0, bandTop, w, h), dest,
                        code -> onSceneCopied(code, dest, decor), sceneHandler());
            } else {
                // 低版本无 Rect 重载：整窗拷入全尺寸图再缩小（现行设备均为高版本，几乎不走这里）
                final Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                android.view.PixelCopy.request(win, full,
                        code -> {
                            if (code == android.view.PixelCopy.SUCCESS) {
                                Bitmap small = Bitmap.createScaledBitmap(full,
                                        dest.getWidth(), dest.getHeight(), true);
                                new android.graphics.Canvas(dest).drawBitmap(small, 0, 0, null);
                                small.recycle();
                            }
                            full.recycle();
                            onSceneCopied(code, dest, decor);
                        }, sceneHandler());
            }
        } catch (Exception e) {
            sceneCopyBusy = false;
            sceneDirty = true;
        }
    }

    /** 把场景帧预模糊成雾图：金字塔两轮（原图→1/4→原图→1/4→原图），
     *  第二轮磨平盒式块感、近似高斯；仍是小图上的几次缩放，远比着色器逐像素卷积便宜。 */
    private static void buildSceneHaze(Bitmap src) {
        int bw = src.getWidth(), bh = src.getHeight();
        int qw = Math.max(12, bw / 4), qh = Math.max(12, bh / 4);
        if (sceneHazeA == null || sceneHazeA.getWidth() != qw || sceneHazeA.getHeight() != qh) {
            sceneHazeA = Bitmap.createBitmap(qw, qh, Bitmap.Config.ARGB_8888);
        }
        Bitmap out = sceneHazeOuts[sceneHazeIdx];
        if (out == null || out.getWidth() != bw || out.getHeight() != bh) {
            out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            sceneHazeOuts[sceneHazeIdx] = out;
        }
        android.graphics.Rect rQ = new android.graphics.Rect(0, 0, qw, qh);
        android.graphics.Rect rB = new android.graphics.Rect(0, 0, bw, bh);
        new android.graphics.Canvas(sceneHazeA).drawBitmap(src, null, rQ, sceneBlurPaint);
        new android.graphics.Canvas(out).drawBitmap(sceneHazeA, null, rB, sceneBlurPaint);
        new android.graphics.Canvas(sceneHazeA).drawBitmap(out, null, rQ, sceneBlurPaint);
        new android.graphics.Canvas(out).drawBitmap(sceneHazeA, null, rB, sceneBlurPaint);
        sceneHazeBmp = out; // 整张换引用发布，渲染线程不会读到画一半的雾图
        sceneHazeIdx ^= 1;
    }

    private static void onSceneCopied(int code, Bitmap dest, View decor) {
        sceneCopyBusy = false;
        if (code == android.view.PixelCopy.SUCCESS) {
            sceneFailStreak = 0;
            sceneOriginY = scenePendingOriginY;
            sceneBmp = dest;
            sceneRef = new java.lang.ref.WeakReference<>(decor);
            sceneBufIdx = (sceneBufIdx + 1) % sceneBufs.length; // 三缓冲轮转：发布帧不被下一帧踩、雾图线程有时间读完
            GlassDrawable.refreshScene();
            // 雾图在本后台线程异步生成（双缓冲整张发布）；代际计数丢弃过期任务，只做最新帧
            final int gen = ++sceneHazeGen;
            final Bitmap hazeSrc = dest;
            sceneHandler().post(() -> {
                if (gen != sceneHazeGen) return;
                buildSceneHaze(hazeSrc);
                GlassDrawable.refreshScene();
            });
            // 拷贝期间又有新变化：不等下一个 vsync，立刻接力下一帧，主线程只负责发请求
            if (sceneDirty && sceneLoopOn) sceneMainHandler.post(sceneRetry);
        } else {
            sceneDirty = true; // 下一帧重试
            if (++sceneFailStreak >= 30) sceneLoopOn = false; // 持续失败则停摆，浮层玻璃自动退回背景层
        }
    }

    public static void publishBackdrop(View ref, Bitmap bmp, boolean crop) {
        backdropRef = new java.lang.ref.WeakReference<>(ref);
        backdropBmp = bmp;
        backdropCrop = crop;
    }

    public static void clearBackdrop() {
        backdropBmp = null;
        backdropRef = null;
    }

    private static Bitmap decodeRaw(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        long stamp = f.lastModified();
        if (rawCache != null && stamp == rawStamp) return rawCache;
        rawCache = BitmapFactory.decodeFile(f.getAbsolutePath());
        rawStamp = stamp;
        return rawCache;
    }

    /** 在玻璃切片上合成一层遮罩色，使切片亮度与卡片四周实际所见一致（消除"贴纸感"）。 */
    private static Bitmap withScrim(Bitmap bmp, int scrimColor) {
        if (bmp == null) return null;
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        c.drawColor(scrimColor);
        return bmp;
    }

    /** 动态背景：把当前帧以小图发布为玻璃切片来源（约每 0.8 秒一帧，足够磨砂质感跟随）。 */
    public static void publishAnimFrame(View animView, int fx, int scrimColor) {
        try {
            int w = animView.getWidth(), h = animView.getHeight();
            if (w <= 0 || h <= 0) return;
            int bw = Math.max(48, w / 5), bh = Math.max(48, h / 5);
            if (animSnap == null || animSnap.getWidth() != bw || animSnap.getHeight() != bh || animSnapFx != fx) {
                animSnap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                animSnapFx = fx;
            }
            android.graphics.Canvas cv = new android.graphics.Canvas(animSnap);
            cv.scale(bw / (float) w, bh / (float) h);
            animView.draw(cv);
            cv.scale(w / (float) bw, h / (float) bh);
            cv.drawColor(scrimColor); // 合成动画上方的遮罩，保证切片与四周亮度一致
            // 每帧实时发布（1/5 尺寸采样自带柔化），玻璃里的背景与外面同步流动，不再定格
            publishBackdrop(animView, animSnap, false);
        } catch (Exception ignored) {
        }
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "app_bg.jpg");
    }

    public static boolean hasBg(Context ctx) {
        return file(ctx).exists();
    }

    public static boolean blurOn(Context ctx) {
        return prefs(ctx).getBoolean(Palette.KEY_BLUR, false);
    }

    public static int fxOn(Context ctx) {
        return prefs(ctx).getInt(Palette.KEY_BG_FX, 0);
    }

    public static boolean glassOn(Context ctx) {
        return prefs(ctx).getBoolean(Palette.KEY_GLASS, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE);
    }

    /** 导入用户选择的图片为背景（最长边压到 2048），成功返回 true。 */
    public static boolean importBg(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) return false;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int max = Math.max(w, h);
            if (max > 3200) {
                float k = 3200f / max;
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * k), Math.round(h * k), true);
            }
            try (FileOutputStream out = new FileOutputStream(file(ctx))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 96, out);
            }
            cached = null;
            cachedStamp = -1;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearBg(Context ctx) {
        file(ctx).delete();
        cached = null;
        cachedStamp = -1;
    }

    /** 模糊等级：0 无，1 玻璃磨砂（中），2 高斯模糊（强）。开玻璃时背景自动磨砂。 */
    private static int blurLevel(Context ctx) {
        if (blurOn(ctx)) return 2;
        if (glassOn(ctx)) return 1;
        return 0;
    }

    private static Bitmap loadProcessed(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return null;
        long stamp = f.lastModified();
        int level = blurLevel(ctx);
        if (cached != null && stamp == cachedStamp && level == cachedLevel) return cached;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) return null;
        if (level == 1) bmp = fakeBlur(bmp, 9);
        else if (level >= 2) bmp = fakeBlur(bmp, 22);
        cached = bmp;
        cachedStamp = stamp;
        cachedLevel = level;
        return bmp;
    }

    /** 缩小再放大实现的快速模糊（全 API 可用）。 */
    private static Bitmap fakeBlur(Bitmap src, int divisor) {
        int w = Math.max(1, src.getWidth() / divisor);
        int h = Math.max(1, src.getHeight() / divisor);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        return Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
    }

    /**
     * 把背景层垫到 root（FrameLayout）最底层。inner 是 root 内原本带不透明背景的子视图
     * （编辑页的 root 布局），激活背景时会被设为透明；不需要时传 null。
     */
    public static void apply(Activity act, FrameLayout root, Palette pal, View inner) {
        apply(act, root, pal, inner, true);
    }

    public static final String TAG_BG = "monet_bg_layer";

    public static void apply(Activity act, FrameLayout root, Palette pal, View inner, boolean isMainPage) {
        // 幂等：先清掉上一次垫入的背景层，避免重复调用时多层叠加互相闪烁
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (TAG_BG.equals(root.getChildAt(i).getTag())) root.removeViewAt(i);
        }
        int scope = prefs(act).getInt(Palette.KEY_BG_SCOPE, 0);
        glassWanted = glassOn(act);
        if ((scope == 1 && !isMainPage) || (scope == 2 && isMainPage)) {
            root.setBackgroundColor(pal.background);
            clearBackdrop();
            return;
        }
        boolean has = hasBg(act);
        boolean glass = glassOn(act);
        int fx = fxOn(act);
        if (!has && !glass && fx == 0) {
            root.setBackgroundColor(pal.background);
            clearBackdrop();
            return;
        }
        root.setBackgroundColor(0x00000000);
        if (inner != null) inner.setBackgroundColor(0x00000000);

        if (fx > 0) {
            // 动态背景优先于静态图片；玻璃切片由 AnimBgView 周期快照发布
            clearBackdrop();
            AnimBgView anim = new AnimBgView(act, fx, pal);
            anim.setTag(TAG_BG);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(anim, 0, alp);
            View scrim2 = new View(act);
            scrim2.setTag(TAG_BG);
            scrim2.setBackgroundColor(Palette.withAlpha(pal.background, 0x26));
            root.addView(scrim2, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        ImageView bg = new ImageView(act);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (has) {
            Bitmap bmp = loadProcessed(act);
            if (bmp != null) bg.setImageBitmap(bmp);
            Bitmap raw = decodeRaw(act);
            if (raw != null) {
                // 玻璃切片：原图强模糊小图（采样时按 CENTER_CROP 对齐）
                int bw = Math.max(40, raw.getWidth() / 12), bh = Math.max(40, raw.getHeight() / 12);
                Bitmap small = Bitmap.createScaledBitmap(raw, bw, bh, true);
                Bitmap bd = fakeBlur(small, 4);
                // 与 apply 里遮罩层同色同透明度合成，保证切片与四周无缝
                withScrim(bd, Palette.withAlpha(pal.background, glass ? 0x2E : 0x8C));
                publishBackdrop(bg, bd, true);
            }
        } else {
            GradientDrawable grad = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Palette.withAlpha(pal.primary, 0xB4), pal.primaryContainer, pal.background});
            bg.setImageDrawable(grad);
            Bitmap gb = Bitmap.createBitmap(270, 480, Bitmap.Config.ARGB_8888);
            grad.setBounds(0, 0, 270, 480);
            grad.draw(new android.graphics.Canvas(gb));
            withScrim(gb, Palette.withAlpha(pal.background, 0x2E));
            publishBackdrop(bg, gb, false);
        }
        bg.setTag(TAG_BG);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(bg, 0, lp);

        // 遮罩层保证文字可读：有背景图时浓一些，纯玻璃渐变时淡一些
        View scrim = new View(act);
        scrim.setTag(TAG_BG);
        scrim.setBackgroundColor(Palette.withAlpha(pal.background, has && !glass ? 0x8C : 0x2E));
        root.addView(scrim, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
