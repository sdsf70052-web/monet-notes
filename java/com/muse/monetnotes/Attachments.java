package com.muse.monetnotes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 附件：从系统文件选择器导入，复制到应用私有目录，通过自有 ContentProvider 对外打开/分享。 */
public class Attachments {
    public static final String AUTHORITY = "com.muse.monetnotes.files";
    public static final long MAX_SIZE = 50L * 1024 * 1024; // 50MB

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "attachments");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context ctx, Note.Attachment a) {
        return new File(dir(ctx), a.file);
    }

    public static Uri uriOf(Note.Attachment a) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(a.file).build();
    }

    /** 把选择器返回的 Uri 复制进应用目录，返回附件对象；失败返回 null。 */
    public static Note.Attachment copyIn(Context ctx, Uri uri) {
        ContentResolver cr = ctx.getContentResolver();
        String name = "附件";
        long size = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (size > MAX_SIZE) {
            Toast.makeText(ctx, "文件超过 50MB，未导入：" + name, Toast.LENGTH_SHORT).show();
            return null;
        }
        String mime = cr.getType(uri);
        if (mime == null) mime = "application/octet-stream";
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) ext = name.substring(dot);
        String stored = "att_" + UUID.randomUUID().toString().replace("-", "") + ext;
        File out = new File(dir(ctx), stored);
        long written = 0;
        try (InputStream in = cr.openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                written += r;
                if (written > MAX_SIZE) {
                    out.delete();
                    Toast.makeText(ctx, "文件超过 50MB，未导入：" + name, Toast.LENGTH_SHORT).show();
                    return null;
                }
                fos.write(buf, 0, r);
            }
        } catch (Exception e) {
            out.delete();
            return null;
        }
        return new Note.Attachment(UUID.randomUUID().toString(), name, mime, stored, written);
    }

    /** 深拷贝一个附件文件，返回新的附件记录（复制笔记时用）。 */
    public static Note.Attachment duplicate(Context ctx, Note.Attachment a) {
        File src = fileOf(ctx, a);
        if (!src.exists()) return null;
        String ext = "";
        int dot = a.file.lastIndexOf('.');
        if (dot > 0) ext = a.file.substring(dot);
        String stored = "att_" + UUID.randomUUID().toString().replace("-", "") + ext;
        File out = new File(dir(ctx), stored);
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
        } catch (Exception e) {
            out.delete();
            return null;
        }
        return new Note.Attachment(UUID.randomUUID().toString(), a.name, a.mime, stored, out.length());
    }

    /** 用系统应用打开附件。 */
    public static void open(Context ctx, Note.Attachment a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uriOf(a), a.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(i, "打开附件"));
        } catch (Exception e) {
            Toast.makeText(ctx, "没有找到能打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 解码图片缩略图（采样压缩，避免 OOM）。文件不存在或非图片返回 null。 */
    public static Bitmap thumbnail(Context ctx, Note.Attachment a, int targetPx) {
        try {
            File f = fileOf(ctx, a);
            if (!f.exists()) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    public static String iconFor(String mime) {
        if (mime == null) return "📎";
        if (mime.startsWith("image/")) return "🖼";
        if (mime.equals("application/pdf")) return "📕";
        if (mime.startsWith("video/")) return "🎬";
        if (mime.startsWith("audio/")) return "🎵";
        if (mime.contains("spreadsheet") || mime.contains("excel") || mime.equals("text/csv")) return "📊";
        if (mime.contains("presentation") || mime.contains("powerpoint")) return "📽";
        if (mime.contains("word") || mime.startsWith("text/")) return "📄";
        if (mime.contains("zip") || mime.contains("compressed") || mime.contains("rar")) return "🗜";
        return "📎";
    }

    public static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / 1024.0 / 1024.0);
    }

    /** 删除不再被任何笔记引用的附件文件（冷启动时调用，让撤销窗口内的文件不会被误删）。 */
    public static void cleanupOrphans(Context ctx) {
        try {
            Set<String> used = new HashSet<>();
            for (Note n : NoteStore.load(ctx)) for (Note.Attachment a : n.attachments) used.add(a.file);
            File[] files = dir(ctx).listFiles();
            if (files == null) return;
            for (File f : files) if (!used.contains(f.getName())) f.delete();
        } catch (Exception ignored) {
        }
    }
}
