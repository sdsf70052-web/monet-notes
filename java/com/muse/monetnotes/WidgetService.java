package com.muse.monetnotes;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 小部件列表的数据源：与主页「全部」相同的过滤与排序（不含归档/回收站）。 */
public class WidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private List<Note> notes = new ArrayList<>();
        private Palette pal;

        Factory(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            Lang.init(ctx);
            pal = Palette.of(ctx);
            List<Note> shown = new ArrayList<>();
            for (Note n : NoteStore.load(ctx)) if (!n.trashed && !n.archived) shown.add(n);
            final int sort = ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE)
                    .getInt(Palette.KEY_SORT, 0);
            Collections.sort(shown, (a, b) -> {
                if (sort == 3) {
                    int c = Long.compare(a.order, b.order);
                    return c != 0 ? c : Long.compare(b.updatedAt, a.updatedAt);
                }
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                if (sort == 1) return Long.compare(b.createdAt, a.createdAt);
                if (sort == 2) return a.title.compareToIgnoreCase(b.title);
                return Long.compare(b.updatedAt, a.updatedAt);
            });
            if (shown.size() > 100) shown = new ArrayList<>(shown.subList(0, 100));
            notes = shown;
        }

        @Override
        public void onDestroy() {
            notes = new ArrayList<>();
        }

        @Override
        public int getCount() {
            return notes.size();
        }

        private static String firstLine(String s) {
            if (s == null) return "";
            String t = s.trim();
            int nl = t.indexOf('\n');
            return (nl >= 0 ? t.substring(0, nl) : t).trim();
        }

        @Override
        public RemoteViews getViewAt(int pos) {
            Note n = notes.get(pos);
            if (pal == null) pal = Palette.of(ctx);
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
            String title;
            String extra;
            if (n.encrypted) {
                // 小部件在桌面上、绕过应用指纹锁，加密笔记永远只显示已加密
                title = Lang.t("🔒 已加密");
                extra = "";
            } else {
                title = !n.title.isEmpty() ? n.title : firstLine(Markdown.strip(n.plainText()));
                if (title.isEmpty()) title = Lang.t("无标题");
                if (n.checklist) {
                    int done = 0;
                    for (Note.CheckItem it : n.items) if (it.done) done++;
                    extra = "☑ " + done + "/" + n.items.size();
                } else if (!n.title.isEmpty()) {
                    extra = firstLine(Markdown.strip(n.content));
                } else {
                    extra = "";
                }
            }
            if (n.pinned) title = "📌 " + title;
            rv.setTextViewText(R.id.row_title, title);
            rv.setTextColor(R.id.row_title, pal.textPrimary);

            String time = new SimpleDateFormat(Lang.t("M月d日 HH:mm"), Locale.getDefault())
                    .format(new Date(n.updatedAt));
            rv.setTextViewText(R.id.row_sub, extra.isEmpty() ? time : time + " · " + extra);
            rv.setTextColor(R.id.row_sub, pal.textSecondary);

            boolean hasColor = n.customColor != 0 || n.colorIndex > 0;
            rv.setViewVisibility(R.id.row_dot, hasColor ? View.VISIBLE : View.INVISIBLE);
            if (hasColor) rv.setInt(R.id.row_dot, "setColorFilter", pal.cardColor(n) | 0xFF000000);
            rv.setOnClickFillInIntent(R.id.row_root, new Intent().putExtra("note_id", n.id));
            return rv;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int pos) {
            return notes.get(pos).id.hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
