package com.muse.monetnotes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * 桌面小部件：头部（点标题进主页、＋ 新建）+ 最近笔记列表（点条目直达编辑）。
 * 列表内容由 WidgetService 提供；笔记数据变化时 NoteStore 会调 requestUpdate 刷新。
 * 颜色全部取自 Palette，与应用内主题保持一致（液态玻璃在桌面宿主里无法实现，用主题底色）。
 */
public class NotesWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Lang.init(ctx);
        for (int id : ids) mgr.updateAppWidget(id, buildViews(ctx, id));
    }

    static RemoteViews buildViews(Context ctx, int appWidgetId) {
        Palette pal = Palette.of(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        rv.setInt(R.id.widget_bg, "setColorFilter", pal.surface);
        rv.setTextViewText(R.id.widget_title, Lang.t("莫奈备忘录"));
        rv.setTextColor(R.id.widget_title, pal.textPrimary);
        rv.setTextColor(R.id.widget_new, pal.primary);
        rv.setTextViewText(R.id.widget_empty, Lang.t("还没有笔记"));
        rv.setTextColor(R.id.widget_empty, pal.textSecondary);

        Intent svc = new Intent(ctx, WidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        svc.setData(Uri.parse("monet://noteswidget/" + appWidgetId));
        rv.setRemoteAdapter(R.id.widget_list, svc);
        rv.setEmptyView(R.id.widget_list, R.id.widget_empty);

        Intent tpl = new Intent(ctx, EditorActivity.class);
        rv.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(ctx, appWidgetId, tpl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        rv.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(ctx, 100000 + appWidgetId,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        rv.setOnClickPendingIntent(R.id.widget_new, PendingIntent.getActivity(ctx, 200000 + appWidgetId,
                new Intent(ctx, EditorActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return rv;
    }

    /** 笔记数据变化后调用：刷新全部已添加的小部件（没有小部件时直接返回）。 */
    public static void requestUpdate(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NotesWidget.class));
            if (ids == null || ids.length == 0) return;
            Lang.init(ctx);
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
            for (int id : ids) mgr.updateAppWidget(id, buildViews(ctx, id));
        } catch (Exception ignored) {
        }
    }
}
