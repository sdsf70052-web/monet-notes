package com.muse.monetnotes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** 只读 ContentProvider：把应用私有目录里的附件以 content:// 形式提供给系统打开/分享。 */
public class AttachProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    private File resolve(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "exp".equals(seg.get(0))) {
            // 导出文件：files/exports/<name>
            String name = seg.get(1);
            if (name.contains("/") || name.contains("..")) return null;
            File d = new File(getContext().getFilesDir(), "exports");
            return new File(d, name);
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) return null;
        return new File(Attachments.dir(getContext()), name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String ext = name.substring(dot + 1).toLowerCase();
                if ("md".equals(ext)) return "text/markdown";
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override
    public int delete(Uri uri, String s, String[] sa) { return 0; }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] sa) { return 0; }
}
