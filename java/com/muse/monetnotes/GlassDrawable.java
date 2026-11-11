package com.muse.monetnotes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 真·液态玻璃（参照 Liquid Glass 的光学构成）：
 * 1) 背景高斯模糊：绘制 BgHelper 发布的模糊背景图中与本控件位置对齐的切片（磨砂透光）；
 * 2) 边缘折射：仅外圈约 6dp 的环带内把背景采样轻微放大，模拟光线在玻璃边缘弯折（中间不变形）；
 * 3) 边缘高光 + 顶部镜面亮线 + 斜向高光，配圆角描边呈现浮起的玻璃边缘。
 * 没有可用背景切片时退化为半透明色面 + 高光（仍比普通半透明更接近玻璃）。
 */
public class GlassDrawable extends Drawable {
    private final int fillColor;
    private final float radius;
    private final float density;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final android.graphics.ColorMatrixColorFilter SAT_FILTER = makeSatFilter();

    /** 透过真玻璃的色彩会更鲜艳：采样时饱和度 +32%、亮度微升。 */
    private static android.graphics.ColorMatrixColorFilter makeSatFilter() {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(1.32f);
        android.graphics.ColorMatrix bright = new android.graphics.ColorMatrix(new float[]{
                1.04f, 0, 0, 0, 6,
                0, 1.04f, 0, 0, 6,
                0, 0, 1.04f, 0, 6,
                0, 0, 0, 1, 0});
        cm.postConcat(bright);
        return new android.graphics.ColorMatrixColorFilter(cm);
    }
    private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int alpha = 255;

    // ---- AGSL 真折射（API 33+）：逐像素 SDF 边缘折射 + 色散，与 LiquidGlassUI/Kyant backdrop 同类实现 ----
    private static Object runtimeShader; // android.graphics.RuntimeShader（低版本不加载）
    private static boolean shaderFailed = false;
    private android.graphics.BitmapShader inputShader;
    private Bitmap inputBmp;
    private android.graphics.BitmapShader inputHazeShader;
    private Bitmap inputHazeBmp;
    /** 按压液态形变 0..1（Ui.elasticPress 驱动）：圆角膨胀、透镜加深、染色加厚。 */
    float pressDeform;
    private float lastRadiusEff = -1f;

    void setPressDeform(float d) {
        if (Math.abs(d - pressDeform) < 0.002f) return;
        pressDeform = d;
        invalidateSelf();
    }
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // 着色器移植自 FlClash 所用 prism_ui 的 shaders/liquid_glass.frag（圆角矩形核心路径）：
    // 圆弧剖面斜面折射（bevel 20dp / refraction 60dp）、着色器内 Vogel 盘高斯磨砂、
    // 色调覆盖随背景明暗自适应（contrast）、上下 rim 光（screen 混合 + 部分纯加光）、
    // 边缘 0.67dp 暗色发丝线（两侧最强）、镜面法线镜像方向的红蓝分离（lens 样式才启用）。
    // 样式参数取 FlClash 的 LiquidGlassStyle.regular（普通表面）与 .pill（浮层栏）原值；
    // 超椭圆角/轮廓/gooey 药丸组等 Flutter 引擎专属部分未移植，圆角按圆形 SDF（差 ≤3% 半径）。
    private static final String AGSL =
            "uniform shader uBackdrop;\n" +
            "uniform float2 uSize;\n" +
            "uniform float2 uOrigin;\n" +
            "uniform float2 uBoundsXY;\n" +
            "uniform float2 uMapA;\n" +
            "uniform float2 uMapB;\n" +
            "uniform float2 uTexSize;\n" +
            "uniform float uRadius;\n" +
            "uniform float uBevel;\n" +
            "uniform float uRefraction;\n" +
            "uniform float uFrost;\n" +
            "uniform float uHaze;\n" +
            "uniform float uHazeFrost;\n" +
            "uniform float uTexSigma;\n" +
            "uniform float4 uTint;\n" +
            "uniform float2 uSatBright;\n" +
            "uniform float3 uLight;\n" +
            "uniform float uContrast;\n" +
            "uniform float3 uEdge;\n" +
            "uniform float4 uRim;\n" +
            "uniform float2 uPart;\n" +
            "uniform float uLinePx;\n" +
            "uniform float uEndPx;\n" +
            "uniform float uTailPx;\n" +
            "uniform shader uHazeTex;\n" +
            "uniform float uHazeTexReady;\n" +
            "uniform float uLens;\n" +
            "\n" +
            "const float3 kLuma = float3(0.2126, 0.7152, 0.0722);\n" +
            "const float kGoldenAngle = 2.39996322973;\n" +
            "const float kFrostReach = 2.07846096908;\n" +
            "const float kLineCap = 0.298;\n" +
            "\n" +
            "float4 texelRaw(float2 texP) {\n" +
            "  return float4(uBackdrop.eval(clamp(texP, float2(0.5), uTexSize - 0.5)));\n" +
            "}\n" +
            "float4 texelAt(float2 viewP) { return texelRaw(viewP * uMapA + uMapB); }\n" +
            "\n" +
            "float4 frostDisc16(float2 center, float2 reach, float falloff) {\n" +
            "  float decay = exp(-falloff / 16.0);\n" +
            "  float weight = sqrt(decay);\n" +
            "  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));\n" +
            "  float2 ray = float2(1.0, 0.0);\n" +
            "  float4 sum = float4(0.0);\n" +
            "  float total = 0.0;\n" +
            "  for (int i = 0; i < 16; i++) {\n" +
            "    float spread = sqrt((float(i) + 0.5) / 16.0);\n" +
            "    sum += texelRaw(center + ray * reach * spread) * weight;\n" +
            "    total += weight;\n" +
            "    weight *= decay;\n" +
            "    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);\n" +
            "  }\n" +
            "  return sum / total;\n" +
            "}\n" +
            "\n" +
            "float4 frostDisc40(float2 center, float2 reach, float falloff) {\n" +
            "  float decay = exp(-falloff / 40.0);\n" +
            "  float weight = sqrt(decay);\n" +
            "  float2 turn = float2(cos(kGoldenAngle), sin(kGoldenAngle));\n" +
            "  float2 ray = float2(1.0, 0.0);\n" +
            "  float4 sum = float4(0.0);\n" +
            "  float total = 0.0;\n" +
            "  for (int i = 0; i < 40; i++) {\n" +
            "    float spread = sqrt((float(i) + 0.5) / 40.0);\n" +
            "    sum += texelRaw(center + ray * reach * spread) * weight;\n" +
            "    total += weight;\n" +
            "    weight *= decay;\n" +
            "    ray = float2(ray.x * turn.x - ray.y * turn.y, ray.x * turn.y + ray.y * turn.x);\n" +
            "  }\n" +
            "  return sum / total;\n" +
            "}\n" +
            "\n" +
            "float4 sampleFrosted(float2 viewP, float frost) {\n" +
            "  float2 sigma = frost * uMapA;\n" +
            "  sigma = sqrt(max(sigma * sigma - uTexSigma * uTexSigma, float2(0.0)));\n" +
            "  float2 reach = max(sigma - 0.5, 0.0) * kFrostReach;\n" +
            "  if (reach.x <= 0.0 || reach.y <= 0.0) {\n" +
            "    return texelAt(viewP);\n" +
            "  }\n" +
            "  float2 ratio = reach / sigma;\n" +
            "  float falloff = 0.5 * ratio.x * ratio.y;\n" +
            "  float2 center = viewP * uMapA + uMapB;\n" +
            "  float widest = max(sigma.x, sigma.y);\n" +
            "  if (widest <= 2.0) {\n" +
            "    return frostDisc16(center, reach, falloff);\n" +
            "  }\n" +
            "  return frostDisc40(center, reach, falloff);\n" +
            "}\n" +
            "\n" +
            "float4 sampleBehind(float2 viewP) {\n" +
            "  // 只为估背景明暗：用原纹理四点均值即可（原纹理本身已是缩小/预模糊的软图），\n" +
            "  // 绝不在这里再做全卷积磨砂——那是旧版多选栏每像素 200+ 次采样的卡顿源。\n" +
            "  float sp = max(uFrost, 1.5);\n" +
            "  float2 spread = float2(sp, -sp);\n" +
            "  return 0.25 * (texelAt(viewP + spread.xx)\n" +
            "      + texelAt(viewP + spread.yy)\n" +
            "      + texelAt(viewP + spread.xy)\n" +
            "      + texelAt(viewP + spread.yx));\n" +
            "}\n" +
            "\n" +
            "float2 lensMap(float2 viewP) {\n" +
            "  // 整体透镜（Qwen 演示的折射模型）：整面以玻璃中心为轴放大采样，倍率 uLens。\n" +
            "  float2 c = uOrigin + uSize * 0.5;\n" +
            "  return c + (viewP - c) / max(uLens, 0.001);\n" +
            "}\n" +
            "\n" +
            "float4 sampleHazeTex(float2 viewP) {\n" +
            "  return float4(uHazeTex.eval(clamp(viewP * uMapA + uMapB, float2(0.5), uTexSize - 0.5)));\n" +
            "}\n" +
            "\n" +
            "float roundedBoxDistance(float2 p, float2 halfSize, float radius) {\n" +
            "  float2 q = abs(p) - halfSize + radius;\n" +
            "  return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n" +
            "}\n" +
            "\n" +
            "float2 roundedBoxNormal(float2 p, float2 halfSize, float radius) {\n" +
            "  float2 q = abs(p) - halfSize + radius;\n" +
            "  float2 n = (q.x > 0.0 && q.y > 0.0)\n" +
            "      ? normalize(q)\n" +
            "      : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n" +
            "  return n * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 local = coord - uBoundsXY;\n" +
            "  float2 point = uOrigin + local;\n" +
            "  float2 halfSize = uSize * 0.5;\n" +
            "  float halfMinor = min(halfSize.x, halfSize.y);\n" +
            "  float2 centered = local - halfSize;\n" +
            "  float radius = min(uRadius, halfMinor);\n" +
            "  float inset = max(-roundedBoxDistance(centered, halfSize, radius), 0.0);\n" +
            "  float2 normal = roundedBoxNormal(centered, halfSize, radius);\n" +
            "  float edge = inset;\n" +
            "\n" +
            "  bool lens = uRefraction < 0.0;\n" +
            "  float bevel = max(min(uBevel, halfMinor * 0.5), 0.001);\n" +
            "  float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);\n" +
            "  float2 shift;\n" +
            "  if (lens) {\n" +
            "    shift = normal * -uRefraction * smoothstep(0.0, bevel, inset)\n" +
            "        * (1.0 - smoothstep(bevel, halfMinor, inset));\n" +
            "  } else {\n" +
            "    float depth = min(uRefraction, halfMinor) * (1.0 - sqrt(1.0 - rise * rise));\n" +
            "    shift = -normal * depth;\n" +
            "  }\n" +
            "\n" +
            "  float frost = uFrost + uEdge.z * rise * rise;\n" +
            "  float4 base = sampleFrosted(lensMap(point + shift), frost);\n" +
            "  if (uEdge.y > 0.0 && inset < uPart.y) {\n" +
            "    float2 part = normal.yx * 0.5 * uEdge.y\n" +
            "        * (0.6 + 0.4 * abs(normal.y) * max(1.0 - inset / uPart.x, 0.0))\n" +
            "        * (1.0 - smoothstep(uPart.x, uPart.y, inset));\n" +
            "    float4 red = sampleFrosted(lensMap(point + shift + part), frost);\n" +
            "    float4 blue = sampleFrosted(lensMap(point + shift - part), frost);\n" +
            "    base = float4(red.r, base.g, blue.b, max(base.a, max(red.a, blue.a)));\n" +
            "  }\n" +
            "  if (uHaze > 0.0) {\n" +
            "    float4 hazeC = uHazeTexReady > 0.5\n" +
            "        ? sampleHazeTex(lensMap(point + shift))\n" +
            "        : sampleFrosted(lensMap(point + shift), uHazeFrost);\n" +
            "    base = mix(base, hazeC, uHaze);\n" +
            "  }\n" +
            "  float alpha = base.a;\n" +
            "  float3 color = base.rgb / max(alpha, 0.0001);\n" +
            "  color = mix(float3(dot(color, kLuma)), color, uSatBright.x) * uSatBright.y;\n" +
            "  color *= 1.0 - uLight.z * abs(normal.x) * exp(-inset / uEndPx);\n" +
            "\n" +
            "  float clash = 0.0;\n" +
            "  if (uContrast != 0.0) {\n" +
            "    float4 behind = sampleBehind(uOrigin + halfSize)\n" +
            "        + sampleBehind(uOrigin + halfSize * float2(0.4, 0.6))\n" +
            "        + sampleBehind(uOrigin + halfSize * float2(1.6, 0.6))\n" +
            "        + sampleBehind(uOrigin + halfSize * float2(0.4, 1.4))\n" +
            "        + sampleBehind(uOrigin + halfSize * float2(1.6, 1.4));\n" +
            "    behind *= 0.2;\n" +
            "    float behindLuma = dot(behind.rgb / max(behind.a, 0.0001), kLuma);\n" +
            "    clash = clamp(\n" +
            "      (sign(uContrast) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5,\n" +
            "      0.0, 1.0);\n" +
            "  }\n" +
            "  float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uContrast);\n" +
            "  float coverage = alpha * (1.0 - cover) + cover;\n" +
            "  float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;\n" +
            "\n" +
            "  float within = max(edge - uLinePx, 0.0);\n" +
            "  float facing;\n" +
            "  if (lens) {\n" +
            "    facing = smoothstep(0.65, 0.95, abs(normal.y));\n" +
            "  } else {\n" +
            "    float rise2 = normal.y * normal.y;\n" +
            "    float rise4 = rise2 * rise2;\n" +
            "    float rise16 = rise4 * rise4 * rise4 * rise4;\n" +
            "    facing = mix(rise2, 0.5 * rise2 * abs(normal.y) * (1.0 + rise16), uRim.y);\n" +
            "  }\n" +
            "  float band = clamp(1.0 - within / max(uLight.y, 0.001), 0.0, 1.0);\n" +
            "  float tail = clamp(1.0 - max(inset - uLinePx, 0.0) / uTailPx, 0.0, 1.0);\n" +
            "  float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);\n" +
            "  glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;\n" +
            "\n" +
            "  float line = clamp((uLinePx - edge) + 0.5, 0.0, 1.0);\n" +
            "  float across = abs(normal.x);\n" +
            "  float darken = mix(uEdge.x, uRim.w, across * across * across);\n" +
            "  if ((uEdge.x > 0.0 || uRim.w > 0.0) && line > 0.0) {\n" +
            "    float4 under = texelAt(lensMap(point));\n" +
            "    float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);\n" +
            "    glass = mix(glass, mix(under.rgb, dark, darken), line);\n" +
            "    coverage = mix(coverage, under.a, line);\n" +
            "  }\n" +
            "\n" +
            "  glass = min(glass, float3(coverage));\n" +
            "  return half4(float4(glass, coverage));\n" +
            "}\n";

    /** 全部存活的玻璃面注册表：动态背景每帧驱动它们重绘，玻璃内外的画面才同步。 */
    private static final java.util.Set<GlassDrawable> LIVE =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** 由动态背景的帧驱动调用：让所有可见的玻璃面用最新一帧背景重绘自己。 */
    public static void refreshLive() {
        for (GlassDrawable d : new java.util.ArrayList<>(LIVE)) {
            Object cb = d.getCallback();
            if (cb instanceof View) {
                View v = (View) cb;
                if (v.isAttachedToWindow() && v.isShown()
                        && v.getWindowVisibility() == View.VISIBLE) {
                    d.invalidateSelf();
                }
            }
        }
    }

    public GlassDrawable(int fillColor, float radiusPx) {
        this.fillColor = fillColor;
        this.radius = radiusPx;
        bmpPaint.setColorFilter(SAT_FILTER);
        synchronized (LIVE) { LIVE.add(this); }
        this.density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        path.reset();
        if (bounds.width() > 0 && bounds.height() > 0) {
            path.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
        }
    }

    /** 上次 draw 时 AGSL 物理光路是否已接管（折射+镜面光都在着色器里算完）。 */
    private boolean shaderRendered;

    /** true = 采样全场景录制（浮层玻璃：透出其下方的真实界面）；false = 只采样背景层。 */
    boolean sceneSource;

    /** FlClash 样式：0 = regular（卡片等普通表面），1 = pill（多选栏/撤销栏等浮层）。 */
    static final int STYLE_REGULAR = 0;
    static final int STYLE_PILL = 1;
    int glassStyle = STYLE_REGULAR;

    /** 全场景录制进行中：此期间所有玻璃只画色调面（录到的卡片呈半透明状即可），
     *  绝不在录制用的软件画布上执行 GPU 着色器——那会报错并毒化全局着色器状态。 */
    static volatile boolean capturingScene;

    /** root 内是否有可见的浮层玻璃（决定要不要录制全场景）。 */
    static boolean anySceneShownIn(View root) {
        for (GlassDrawable d : new java.util.ArrayList<>(LIVE)) {
            if (!d.sceneSource) continue;
            Object cb = d.getCallback();
            if (cb instanceof View) {
                View v = (View) cb;
                if (v.isAttachedToWindow() && v.isShown()
                        && v.getWindowVisibility() == View.VISIBLE && isDescendant(v, root)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 录制前临时隐藏浮层玻璃自身（否则会把自己录进去），返回被隐藏的视图供恢复。 */
    static java.util.List<View> hideSceneHosts(View root) {
        java.util.List<View> hidden = new java.util.ArrayList<>();
        for (GlassDrawable d : new java.util.ArrayList<>(LIVE)) {
            if (!d.sceneSource) continue;
            Object cb = d.getCallback();
            if (cb instanceof View) {
                View v = (View) cb;
                if (v.getVisibility() == View.VISIBLE && isDescendant(v, root)) {
                    v.setVisibility(View.INVISIBLE);
                    hidden.add(v);
                }
            }
        }
        return hidden;
    }

    static void restoreSceneHosts(java.util.List<View> hidden) {
        for (View v : hidden) v.setVisibility(View.VISIBLE);
    }

    private static boolean isDescendant(View v, View root) {
        Object p = v.getParent();
        while (p instanceof View) {
            if (p == root) return true;
            p = ((View) p).getParent();
        }
        return v == root;
    }

    /** PixelCopy 拷到新场景帧后调用：只让浮层玻璃重绘采样（卡片等背景层玻璃不受打扰）。 */
    public static void refreshScene() {
        for (GlassDrawable d : new java.util.ArrayList<>(LIVE)) {
            if (!d.sceneSource) continue;
            Object cb = d.getCallback();
            if (cb instanceof View) {
                View v = (View) cb;
                if (v.isAttachedToWindow() && v.isShown()) d.invalidateSelf();
            }
        }
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        boolean dark = Ui.GLASS_DARK;
        shaderRendered = false;
        float rEff = radius + density * 10f * pressDeform;
        if (Math.abs(rEff - lastRadiusEff) > 0.05f) {
            lastRadiusEff = rEff;
            path.reset();
            path.addRoundRect(new android.graphics.RectF(b),
                    rEff, rEff, android.graphics.Path.Direction.CW);
        }
        canvas.save();
        canvas.clipPath(path);
        drawBackdrop(canvas, b);
        if (shaderRendered) {
            // prism 着色器已一次算完采样+折射+磨砂+色调+rim 光+暗线，不再叠加任何手绘层
            canvas.restore();
            return;
        }
        // 色调层（决定玻璃的乳白/烟熏程度）
        fillPaint.setShader(null);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(withAlpha(fillColor, alpha));
        canvas.drawPath(path, fillPaint);
        // 上半部宽幅柔光（参考图里玻璃上半截的面光）
        fxPaint.setStyle(Paint.Style.FILL);
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.left, b.top + b.height() * 0.62f,
                new int[]{withAlpha(dark ? 0x1CFFFFFF : 0x24FFFFFF, alpha), withAlpha(0x00FFFFFF, alpha)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fxPaint);
        // 斜向高光（左上入射的镜面反射）
        fxPaint.setShader(new LinearGradient(b.left, b.top,
                b.left + b.width() * 0.85f, b.top + b.height(),
                new int[]{withAlpha(0x30FFFFFF, alpha), withAlpha(0x00FFFFFF, alpha)},
                new float[]{0f, 0.55f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fxPaint);
        // 纵向明暗：顶部透亮、底部微沉，模拟玻璃厚度
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom,
                new int[]{withAlpha(dark ? 0x14FFFFFF : 0x1AFFFFFF, alpha),
                        withAlpha(0x00FFFFFF, alpha),
                        withAlpha(dark ? 0x14000000 : 0x0A000000, alpha)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fxPaint);
        // 角落光斑：光在圆角处聚集（参考图四角最亮）
        float glowScale = Math.min(1f, b.width() / (radius * 4f + 1f));
        if (glowScale > 0.25f && radius > density * 4) {
            float gr = radius * 1.7f;
            int ga = (int) (0x2A * glowScale);
            fxPaint.setShader(new android.graphics.RadialGradient(
                    b.left + radius * 0.62f, b.top + radius * 0.5f, gr,
                    withAlpha(ga << 24 | 0xFFFFFF, alpha), 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawPath(path, fxPaint);
            fxPaint.setShader(new android.graphics.RadialGradient(
                    b.right - radius * 0.62f, b.top + radius * 0.5f, gr,
                    withAlpha(ga << 24 | 0xFFFFFF, alpha), 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawPath(path, fxPaint);
        }
        fxPaint.setShader(null);
        canvas.restore();

        // —— 边缘光系统（参考图最醒目的特征：发亮的玻璃边） ——
        // 1) 外圈光晕：宽而淡，向外漏光
        fxPaint.setStyle(Paint.Style.STROKE);
        fxPaint.setStrokeWidth(density * 3f);
        fxPaint.setColor(withAlpha(dark ? 0x24FFFFFF : 0x30FFFFFF, alpha));
        canvas.drawPath(path, fxPaint);
        // 2) 亮芯：细而亮的折射主线，亮度沿周长分布（顶部最亮、两侧中、底部带反射）
        fxPaint.setStrokeWidth(Math.max(1.2f, density * 1.3f));
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom,
                new int[]{withAlpha(0xE8FFFFFF, alpha), withAlpha(0x5CFFFFFF, alpha), withAlpha(0x94FFFFFF, alpha)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fxPaint);
        fxPaint.setShader(null);
        // 3) 顶部镜面亮线：中间近乎纯白，向两侧渐隐
        float insetY = Math.max(1f, density * 0.9f);
        fxPaint.setStrokeWidth(Math.max(1.2f, density * 1.5f));
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.right, b.top,
                new int[]{0x00FFFFFF, withAlpha(0xF5FFFFFF, alpha), 0x00FFFFFF},
                new float[]{0.04f, 0.5f, 0.96f}, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.clipPath(path);
        canvas.drawLine(b.left + radius * 0.45f, b.top + insetY, b.right - radius * 0.45f, b.top + insetY, fxPaint);
        // 4) 底部反射线：弱一档，像光从下方台面弹回来
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.right, b.top,
                new int[]{0x00FFFFFF, withAlpha(0x59FFFFFF, alpha), 0x00FFFFFF},
                new float[]{0.1f, 0.5f, 0.9f}, Shader.TileMode.CLAMP));
        canvas.drawLine(b.left + radius * 0.6f, b.bottom - insetY, b.right - radius * 0.6f, b.bottom - insetY, fxPaint);
        canvas.restore();
        // 5) 内斜面：内侧一圈由亮到暗的薄边，做出玻璃的厚度
        RectF innerF = new RectF(b);
        float bev = density * 1.6f;
        innerF.inset(bev, bev);
        Path innerPath = new Path();
        innerPath.addRoundRect(innerF, Math.max(0, radius - bev), Math.max(0, radius - bev), Path.Direction.CW);
        fxPaint.setShader(new LinearGradient(b.left, b.top, b.left, b.bottom,
                new int[]{withAlpha(0x26FFFFFF, alpha), withAlpha(0x00000000, alpha), withAlpha(dark ? 0x20000000 : 0x14000000, alpha)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        fxPaint.setStrokeWidth(Math.max(1f, density * 1f));
        canvas.drawPath(innerPath, fxPaint);
        fxPaint.setShader(null);
    }

    @Override public void getOutline(android.graphics.Outline outline) {
        Rect b = getBounds();
        if (b.width() > 0 && b.height() > 0) outline.setRoundRect(b, radius);
    }

    /** 绘制与控件位置对齐的模糊背景切片 + 边缘环带折射。成功绘制返回 true。 */
    private boolean drawBackdrop(Canvas canvas, Rect b) {
        Bitmap bmp = BgHelper.backdropBmp;
        View ref = BgHelper.backdropView();
        boolean useCrop = BgHelper.backdropCrop;
        boolean isScene = false;
        // 浮层玻璃优先采样全场景（PixelCopy 成品帧，透出下方真实界面）；不可用时退回背景层
        if (sceneSource && BgHelper.sceneBmp != null && BgHelper.sceneView() != null) {
            bmp = BgHelper.sceneBmp;
            ref = BgHelper.sceneView();
            useCrop = false;
            isScene = true;
        }
        if (bmp == null || ref == null || !ref.isAttachedToWindow()) return false;
        Object cb = getCallback();
        if (!(cb instanceof View)) return false;
        View host = (View) cb;
        int vw = ref.getWidth(), vh = ref.getHeight();
        if (vw <= 0 || vh <= 0) return false;
        int[] hl = new int[2], rl = new int[2];
        // 浮层栏住在独立浮窗里，与主窗不在同一坐标系：场景采样统一用屏幕坐标对齐
        if (isScene) { host.getLocationOnScreen(hl); ref.getLocationOnScreen(rl); }
        else { host.getLocationInWindow(hl); ref.getLocationInWindow(rl); }
        float hx = hl[0] - rl[0], hy = hl[1] - rl[1];

        // 背景视图坐标 -> 背景图坐标 的仿射映射：bmp = view * A + B
        float aX, aY, bX = 0, bY = 0;
        boolean crop = useCrop;
        if (crop) {
            float sc = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
            aX = aY = 1f / sc;
            bX = -(vw - bmp.getWidth() * sc) / 2f / sc;
            bY = -(vh - bmp.getHeight() * sc) / 2f / sc;
        } else {
            aX = bmp.getWidth() / (float) vw;
            if (isScene) {
                // 场景帧只含窗口底部条带：纵向映射要扣除条带顶边偏移
                int bandTop = BgHelper.sceneOriginY;
                aY = bmp.getHeight() / (float) Math.max(1, vh - bandTop);
                bY = -bandTop * aY;
            } else {
                aY = bmp.getHeight() / (float) vh;
            }
        }
        float band = Math.max(density * 6f, Math.min(density * 11f, radius * 0.38f));
        // API 33+：AGSL 着色器逐像素真折射（带色散）；失败自动回退到下面的 CPU 采样
        if (android.os.Build.VERSION.SDK_INT >= 33 && !shaderFailed
                && drawBackdropShader(canvas, b, bmp, hx, hy, aX, aY, bX, bY)) {
            return true;
        }
        float vx0 = hx + b.left, vy0 = hy + b.top, vx1 = hx + b.right, vy1 = hy + b.bottom;
        RectF src = new RectF(vx0 * aX + bX, vy0 * aY + bY, vx1 * aX + bX, vy1 * aY + bY);
        clampSrc(src, bmp);
        if (src.width() <= 0 || src.height() <= 0) return false;
        bmpPaint.setAlpha(alpha);
        canvas.drawBitmap(bmp, toRect(src), b, bmpPaint);

        // 边缘折射带：外圈环带内把采样放大 15%，背景内容在边缘明显弯折（中间不变形）
        RectF inner = new RectF(b);
        inner.inset(band, band);
        if (inner.width() > 0 && inner.height() > 0) {
            Path bandPath = new Path();
            bandPath.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
            Path innerPath = new Path();
            float ir = Math.max(0, radius - band);
            innerPath.addRoundRect(inner, ir, ir, Path.Direction.CW);
            bandPath.op(innerPath, Path.Op.DIFFERENCE);
            float cx = src.centerX(), cy = src.centerY();
            float hw = src.width() / 2f / 1.15f, hh = src.height() / 2f / 1.15f;
            RectF src2 = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
            clampSrc(src2, bmp);
            canvas.save();
            canvas.clipPath(bandPath);
            canvas.drawBitmap(bmp, toRect(src2), b, bmpPaint);
            canvas.restore();
        }
        return true;
    }

    /** AGSL 路径：prism_ui 液态玻璃着色器（FlClash 同款光学模型），参数按样式表喂入。 */
    private boolean drawBackdropShader(Canvas canvas, Rect b, Bitmap bmp,
                                       float hx, float hy, float aX, float aY, float bX, float bY) {
        // RuntimeShader 只能跑在硬件加速画布上；软件画布直接跳过，不算失败
        if (!canvas.isHardwareAccelerated()) return false;
        try {
            if (runtimeShader == null) {
                runtimeShader = new android.graphics.RuntimeShader(AGSL);
            }
            android.graphics.RuntimeShader sh = (android.graphics.RuntimeShader) runtimeShader;
            if (inputBmp != bmp) {
                inputBmp = bmp;
                inputShader = new android.graphics.BitmapShader(bmp,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            }
            sh.setInputShader("uBackdrop", inputShader);
            Bitmap hazeBmp = sceneSource ? BgHelper.sceneHazeBmp : null;
            if (inputHazeBmp != hazeBmp) {
                inputHazeBmp = hazeBmp;
                inputHazeShader = hazeBmp == null ? null : new android.graphics.BitmapShader(hazeBmp,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            }
            if (inputHazeShader != null) {
                sh.setInputShader("uHazeTex", inputHazeShader);
                sh.setFloatUniform("uHazeTexReady", 1.0f);
            } else {
                sh.setFloatUniform("uHazeTexReady", 0.0f);
            }
            sh.setFloatUniform("uSize", b.width(), b.height());
            sh.setFloatUniform("uOrigin", hx + b.left, hy + b.top);
            sh.setFloatUniform("uBoundsXY", b.left, b.top);
            sh.setFloatUniform("uMapA", aX, aY);
            sh.setFloatUniform("uMapB", bX, bY);
            sh.setFloatUniform("uTexSize", bmp.getWidth(), bmp.getHeight());
            sh.setFloatUniform("uRadius", radius + density * 10f * pressDeform);
            sh.setFloatUniform("uLens", 1.06f + 0.03f * pressDeform);

            // —— FlClash LiquidGlassStyle 参数表（regular / pill，深浅两套，逐项原值） ——
            boolean dark = Ui.GLASS_DARK;
            float sat, contrast, tintA, rim, rimWidthDp, rimTail, rimFocus, rimAdded;
            float hairline, hairlineSide, blurDp, haze, hazeDp, dispDp, rimBlurDp;
            if (glassStyle == STYLE_PILL) {
                sat = dark ? 1.925f : 2.1f;
                contrast = dark ? -0.4f : 0.4f;
                tintA = dark ? 0.48f : 0x93 / 255f;
                rim = 0.35f; rimWidthDp = 1.3f; rimTail = 0f; rimFocus = 0f; rimAdded = 0f;
                hairline = dark ? 0f : 0.35f; hairlineSide = hairline;
                blurDp = 0f; haze = 0.72f; hazeDp = 15.6f;
                // 浮层栏按 FlClash 的 lens 样式补色散（红蓝沿法线镜像分离）与边缘雾化，
                // 这是用户要的"散射"；regular/pill 本表的 dispersion 为 0，栏上单独启用。
                dispDp = 6.5f; rimBlurDp = 1.5f;
            } else {
                sat = dark ? 2.84f : 2.08f;
                contrast = dark ? -0.4f : 0.4f;
                tintA = dark ? 0.62f : 0x8A / 255f;
                rim = 0.28f; rimWidthDp = 1.2f; rimTail = 0.17f; rimFocus = 1f; rimAdded = 0.38f;
                hairline = dark ? 0f : 0.25f; hairlineSide = 0.75f;
                blurDp = 2.5f; haze = 0f; hazeDp = 0f;
                dispDp = 0f; rimBlurDp = 0f;
            }
            float d = density;
            sh.setFloatUniform("uBevel", 20f * d);
            sh.setFloatUniform("uRefraction", 60f * d);
            sh.setFloatUniform("uFrost", blurDp * d);
            sh.setFloatUniform("uHaze", haze);
            sh.setFloatUniform("uHazeFrost", hazeDp * d);
            // 背景层切片已预模糊（等效大 sigma，不再叠加磨砂）；场景帧的预模糊量随采样档位（BgHelper.sceneSigma）
            sh.setFloatUniform("uTexSigma", sceneSource ? BgHelper.sceneSigma : 8.0f);
            sh.setFloatUniform("uTint",
                    ((fillColor >> 16) & 0xFF) / 255f,
                    ((fillColor >> 8) & 0xFF) / 255f,
                    (fillColor & 0xFF) / 255f, Math.min(1f, tintA + 0.08f * pressDeform));
            sh.setFloatUniform("uSatBright", sat, 1.0f);
            sh.setFloatUniform("uLight", rim, rimWidthDp * d, 0.0f);
            sh.setFloatUniform("uContrast", contrast);
            sh.setFloatUniform("uEdge", hairline, dispDp * d, rimBlurDp * d);
            sh.setFloatUniform("uRim", rimTail, rimFocus, rimAdded, hairlineSide);
            sh.setFloatUniform("uPart", 7f * d, 10f * d);
            sh.setFloatUniform("uLinePx", 0.67f * d);
            sh.setFloatUniform("uEndPx", 3f * d);
            sh.setFloatUniform("uTailPx", 4.5f * d);
            shaderPaint.setShader(sh);
            shaderPaint.setAlpha(alpha);
            canvas.drawPath(path, shaderPaint);
            shaderPaint.setShader(null);
            shaderRendered = true;
            return true;
        } catch (Throwable t) {
            shaderFailed = true;
            return false;
        }
    }

    private Rect toRect(RectF r) {
        return new Rect(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private void clampSrc(RectF src, Bitmap bmp) {
        src.left = Math.max(0, Math.min(src.left, bmp.getWidth() - 1));
        src.top = Math.max(0, Math.min(src.top, bmp.getHeight() - 1));
        src.right = Math.max(src.left, Math.min(src.right, bmp.getWidth()));
        src.bottom = Math.max(src.top, Math.min(src.bottom, bmp.getHeight()));
    }

    private int withAlpha(int color, int a) {
        int base = (color >>> 24);
        return (color & 0x00FFFFFF) | ((base * a / 255) << 24);
    }

    @Override public void setAlpha(int a) { this.alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { fillPaint.setColorFilter(cf); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
