package com.muse.monetnotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

/**
 * 主题色板：优先使用 Android 12+ 的莫奈动态取色（系统基于壁纸生成的
 * system_accent / system_neutral 色阶），关闭或低版本时回退到种子色方案。
 */
public class Palette {
    public int primary;
    public int onPrimary;
    public int primaryContainer;
    public int background;
    public int surface;
    public int surfaceVariant;
    public int textPrimary;
    public int textSecondary;
    public int outline;
    public boolean dark;
    public boolean glass = false;

    // 种子色：紫、蓝、绿、橙、粉
    private static final int[] SEEDS = {0xFF6750A4, 0xFF0061A4, 0xFF386A20, 0xFFA15C00, 0xFFB3265E};
    // 卡片标记色（浅色模式 / 深色模式各一套柔和色调）
    private static final int[] TINTS_LIGHT = {0, 0xFFF9DEDC, 0xFFFFDDB3, 0xFFFFF0B3, 0xFFD3E8C8, 0xFFC4E7E0, 0xFFDCE7FD};
    private static final int[] TINTS_DARK = {0, 0xFF5F3B38, 0xFF5A431F, 0xFF565110, 0xFF37472B, 0xFF21423C, 0xFF37415C};

    public static final String PREFS = "settings";
    public static final String KEY_MODE = "theme_mode"; // 0 跟随系统 1 浅色 2 深色
    public static final String KEY_DYNAMIC = "dynamic_color"; // boolean, default true
    public static final String KEY_SEED = "seed_index"; // 0..4
    public static final String KEY_GRID = "grid_view"; // boolean, default true
    public static final String KEY_SORT = "sort_mode"; // 0 更新 1 创建 2 标题
    public static final String KEY_BLUR = "bg_blur"; // 背景高斯模糊
    public static final String KEY_GLASS = "liquid_glass"; // 液态玻璃
    public static final String KEY_ICON = "app_icon"; // 桌面图标变体下标
    public static final String KEY_BG_SCOPE = "bg_scope"; // 背景生效区域 0 全部 1 仅主页 2 仅编辑页
    public static final String KEY_BG_FX = "bg_fx"; // 动态背景 0 无 1 极光 2 气泡 3 星空 4 波浪 5 流体
    public static final String KEY_FX_LEVEL = "fx_level"; // 效果强度 0 极致 1 均衡 2 流畅（按设备性能取舍采样/帧率）

    public static final String KEY_FX_C_SCALE = "fx_c_scale"; // 自定义：场景采样分辨率 %（10-100）
    public static final String KEY_FX_C_COPY = "fx_c_copy";   // 自定义：拷贝帧率上限 fps（0=不限，5-60）
    public static final String KEY_FX_C_ANIM = "fx_c_anim";   // 自定义：动态背景帧率 fps（5-60）

    /** 当前效果强度档位（拷贝循环与动画驱动每帧现读，改档即时生效、无需重建页面）。 */
    public static int fxLevel(android.content.Context c) {
        return c.getSharedPreferences(PREFS, 0).getInt(KEY_FX_LEVEL, 0);
    }

    /** 场景采样缩放除数：极致 1（不压缩）、均衡 2、流畅 3、自定义按分辨率 % 换算。 */
    public static int fxSceneDiv(android.content.Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        switch (sp.getInt(KEY_FX_LEVEL, 0)) {
            case 1: return 2;
            case 2: return 3;
            case 3: {
                int pct = Math.max(10, Math.min(100, sp.getInt(KEY_FX_C_SCALE, 100)));
                return Math.max(1, Math.round(100f / pct));
            }
            default: return 1;
        }
    }

    /** 拷贝最小间隔（毫秒）：极致不限、均衡约 30fps、流畅约 20fps、自定义按 fps（0=不限）。 */
    public static int fxCopyMinGapMs(android.content.Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        switch (sp.getInt(KEY_FX_LEVEL, 0)) {
            case 1: return 33;
            case 2: return 50;
            case 3: {
                int fps = sp.getInt(KEY_FX_C_COPY, 0);
                return fps <= 0 ? 0 : Math.max(16, 1000 / Math.min(60, fps));
            }
            default: return 0;
        }
    }

    /** 动态背景帧门控（毫秒）：极致/均衡 33（30fps）、流畅 66（15fps）、自定义按 fps。 */
    public static int fxAnimGateMs(android.content.Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        switch (sp.getInt(KEY_FX_LEVEL, 0)) {
            case 2: return 66;
            case 3: {
                int fps = Math.max(5, Math.min(60, sp.getInt(KEY_FX_C_ANIM, 30)));
                return 1000 / fps;
            }
            default: return 33;
        }
    }
    public static final String KEY_SWIPE_FX = "swipe_fx"; // 分类切换特效：0 滑动 1 淡入淡出 2 缩放 3 无

    public static boolean isDark(Context ctx, SharedPreferences sp) {
        int mode = sp.getInt(KEY_MODE, 0);
        if (mode == 1) return false;
        if (mode == 2) return true;
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette of(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Palette p = new Palette();
        p.dark = isDark(ctx, sp);
        boolean dynamic = sp.getBoolean(KEY_DYNAMIC, true);
        int seed = SEEDS[Math.min(Math.max(sp.getInt(KEY_SEED, 0), 0), SEEDS.length - 1)];

        if (dynamic && Build.VERSION.SDK_INT >= 31) {
            // 莫奈动态取色：直接读取系统按壁纸生成的色阶
            if (p.dark) {
                p.primary = sys(ctx, android.R.color.system_accent1_200);
                p.onPrimary = sys(ctx, android.R.color.system_accent1_800);
                p.primaryContainer = sys(ctx, android.R.color.system_accent1_700);
                p.background = sys(ctx, android.R.color.system_neutral1_900);
                p.surface = sys(ctx, android.R.color.system_neutral1_800);
                p.surfaceVariant = sys(ctx, android.R.color.system_neutral2_700);
                p.textPrimary = sys(ctx, android.R.color.system_neutral1_100);
                p.textSecondary = sys(ctx, android.R.color.system_neutral2_300);
                p.outline = sys(ctx, android.R.color.system_neutral2_500);
            } else {
                p.primary = sys(ctx, android.R.color.system_accent1_600);
                p.onPrimary = Color.WHITE;
                p.primaryContainer = sys(ctx, android.R.color.system_accent1_100);
                p.background = sys(ctx, android.R.color.system_neutral1_50);
                p.surface = sys(ctx, android.R.color.system_neutral1_10);
                p.surfaceVariant = sys(ctx, android.R.color.system_neutral2_100);
                p.textPrimary = sys(ctx, android.R.color.system_neutral1_900);
                p.textSecondary = sys(ctx, android.R.color.system_neutral2_600);
                p.outline = sys(ctx, android.R.color.system_neutral2_400);
            }
        } else {
            p.primary = seed;
            p.onPrimary = Color.WHITE;
            if (p.dark) {
                p.primaryContainer = blend(seed, Color.BLACK, 0.45f);
                p.background = 0xFF141218;
                p.surface = 0xFF211F26;
                p.surfaceVariant = 0xFF2E2B33;
                p.textPrimary = 0xFFE6E1E5;
                p.textSecondary = 0xFFB8B0BC;
                p.outline = 0xFF6B6470;
                p.primary = blend(seed, Color.WHITE, 0.35f);
            } else {
                p.primaryContainer = blend(seed, Color.WHITE, 0.78f);
                p.background = 0xFFFEF7FF;
                p.surface = 0xFFFFFFFF;
                p.surfaceVariant = 0xFFEFEAF4;
                p.textPrimary = 0xFF1D1B20;
                p.textSecondary = 0xFF6A6570;
                p.outline = 0xFFB9B2BE;
            }
        }
        // 液态玻璃：表面改为半透明，让全屏背景透出来
        if (sp.getBoolean(KEY_GLASS, false)) {
            p.glass = true;
            // 深色下更烟熏（少乳白、多透光，像参考图的暗色玻璃），浅色保持乳白磨砂
            p.surface = withAlpha(p.surface, p.dark ? 0x5C : 0x80);
            p.surfaceVariant = withAlpha(p.surfaceVariant, p.dark ? 0x4D : 0x6E);
            p.primaryContainer = withAlpha(p.primaryContainer, 0x99);
        }
        Ui.GLASS = p.glass;
        Ui.GLASS_DARK = p.dark;
        return p;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int sys(Context ctx, int resId) {
        return ctx.getColor(resId);
    }

    public static int blend(int c1, int c2, float ratio) {
        int r = (int) (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio);
        int g = (int) (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio);
        int b = (int) (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio);
        return Color.rgb(r, g, b);
    }

    public int cardColor(Note n) {
        int base = cardColorRaw(n);
        return glass ? withAlpha(base, dark ? 0x78 : 0xA3) : base;
    }

    private int cardColorRaw(Note n) {
        if (n.customColor != 0) {
            // 自定义颜色：保留色相，浅色模式提亮成柔和底色，深色模式压暗，保证文字可读
            return dark ? blend(n.customColor, Color.BLACK, 0.45f)
                        : blend(n.customColor, Color.WHITE, 0.62f);
        }
        if (n.colorIndex > 0 && n.colorIndex < TINTS_LIGHT.length) {
            int t = dark ? TINTS_DARK[n.colorIndex] : TINTS_LIGHT[n.colorIndex];
            if (t != 0) return t;
        }
        return surface;
    }

    public static int[] tintColors(boolean dark) {
        return dark ? TINTS_DARK : TINTS_LIGHT;
    }
}
