package com.muse.monetnotes;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** 自定义桌面图标：通过 activity-alias 切换（系统限制，图标只能从预置款式中选）。 */
public class AppIcon {
    public static final String[] NAMES = {"莫奈紫", "天空蓝", "草木绿", "暖阳橙", "樱花粉", "曜石黑"};
    public static final String[] ALIASES = {
            "com.muse.monetnotes.IconDefault",
            "com.muse.monetnotes.IconBlue",
            "com.muse.monetnotes.IconGreen",
            "com.muse.monetnotes.IconOrange",
            "com.muse.monetnotes.IconPink",
            "com.muse.monetnotes.IconDark"};
    public static final int[] RES = {
            R.mipmap.ic_launcher,
            R.mipmap.ic_launcher_blue,
            R.mipmap.ic_launcher_green,
            R.mipmap.ic_launcher_orange,
            R.mipmap.ic_launcher_pink,
            R.mipmap.ic_launcher_dark};

    public static int current(Context ctx) {
        int i = ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE).getInt(Palette.KEY_ICON, 0);
        return Math.min(Math.max(i, 0), ALIASES.length - 1);
    }

    public static void setIcon(Context ctx, int idx) {
        PackageManager pm = ctx.getPackageManager();
        for (int i = 0; i < ALIASES.length; i++) {
            pm.setComponentEnabledSetting(
                    new ComponentName(ctx, ALIASES[i]),
                    i == idx ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                             : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE)
                .edit().putInt(Palette.KEY_ICON, idx).apply();
    }
}
