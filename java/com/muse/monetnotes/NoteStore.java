package com.muse.monetnotes;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class NoteStore {
    private static final String FILE_NAME = "notes.json";
    private static List<Note> cache;

    public static synchronized List<Note> load(Context ctx) {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                in.close();
                if (read > 0) {
                    JSONArray arr = new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
                    for (int i = 0; i < arr.length(); i++) cache.add(Note.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception ignored) {
            }
        }
        if (cache.isEmpty() && !f.exists()) {
            seed(cache);
            save(ctx);
        }
        return cache;
    }

    private static void seed(List<Note> list) {
        Note a = new Note();
        a.title = "欢迎来到莫奈备忘录 🎨";
        a.content = "这是一款会跟随你的壁纸变色的备忘录。\n\n· 点右下角 + 新建笔记\n· 编辑时自动保存，不用点保存键\n· 点上方标题栏的调色盘图标，可以切换深色 / 浅色、开关莫奈动态取色，或换一套种子色\n· 在全部笔记里长按或点卡片上的 ⋮ 菜单，可以置顶、收藏、归档";
        a.pinned = true;
        a.tags.add("指南");
        list.add(a);

        Note b = new Note();
        b.title = "试试清单模式";
        b.checklist = true;
        b.items.add(new Note.CheckItem("新建一条清单笔记", true));
        b.items.add(new Note.CheckItem("在编辑页切换到「清单」模式", false));
        b.items.add(new Note.CheckItem("勾选我，看看进度条变化", false));
        b.tags.add("指南");
        list.add(b);

        Note c = new Note();
        c.title = "小提示";
        c.content = "搜索可以搜标题、正文和标签。\n删除的笔记会先进回收站，在回收站里可以恢复或彻底删除。\n给笔记选一个颜色标记，瀑布流里一眼就能找到它。";
        c.colorIndex = 2;
        list.add(c);
    }

    public static synchronized void save(Context ctx) {
        writeAll(ctx);
    }

    private static void writeAll(Context ctx) {
        if (cache == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Note n : cache) arr.put(n.toJson());
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            FileOutputStream out = new FileOutputStream(f);
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
            // 后台保存时若数据正在被编辑导致序列化异常，放弃本次，下一次自动保存会补上，
            // 绝不写出残缺文件。
        }
    }

    private static final java.util.concurrent.ExecutorService SAVE_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "note-save");
                t.setDaemon(true);
                return t;
            });
    private static volatile boolean asyncQueued = false;

    /** 后台线程保存（编辑页自动保存用）：序列化与写文件都不占用主线程，长笔记不卡顿。
     *  短时间内的多次调用会合并为一次。 */
    public static void saveAsync(Context ctx) {
        if (cache == null) return;
        final Context app = ctx.getApplicationContext();
        if (asyncQueued) return;
        asyncQueued = true;
        SAVE_EXEC.execute(() -> {
            asyncQueued = false;
            synchronized (NoteStore.class) {
                writeAll(app);
            }
        });
    }

    /** 复制一条笔记（可指定目标分类），附件文件一并深拷贝，副本不置顶不归档。 */
    public static synchronized Note duplicate(Context ctx, Note src, String targetCategory, boolean addSuffix) {
        Note c = new Note();
        c.title = (src.title == null ? "" : src.title) + (addSuffix ? " 副本" : "");
        c.content = src.content;
        c.checklist = src.checklist;
        for (Note.CheckItem it : src.items) c.items.add(new Note.CheckItem(it.text, it.done));
        c.tags.addAll(src.tags);
        c.colorIndex = src.colorIndex;
        c.customColor = src.customColor;
        c.category = targetCategory != null ? targetCategory : src.category;
        c.favorite = src.favorite;
        c.reminderAt = src.reminderAt > System.currentTimeMillis() ? src.reminderAt : 0;
        for (Note.Attachment a : src.attachments) {
            Note.Attachment dup = Attachments.duplicate(ctx, a);
            if (dup != null) c.attachments.add(dup);
        }
        load(ctx).add(0, c);
        save(ctx);
        if (c.reminderAt > 0) ReminderManager.sync(ctx, c);
        return c;
    }

    public static Note find(String id) {
        if (cache == null) return null;
        for (Note n : cache) if (n.id.equals(id)) return n;
        return null;
    }
}
