package com.muse.monetnotes;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 云备份（v1.49）：
 * - 备份内容为完整 ZIP：notes.json（全部笔记数据）+ attachments/（附件原文件），换机可完整恢复；
 *   读取时兼容旧版纯 JSON 备份（无附件）。
 * - WebDAV：HttpURLConnection 实现 MKCOL/PUT/GET（Basic 认证），服务器地址由用户填写其 WebDAV 文件夹地址；
 *   备份优先放进地址下的 monet-notes 子文件夹（建不了就直接放根下），恢复时两处都找。
 * - 云盘文件夹：SAF 目录树（系统文件选择器可选网盘提供方），记住授权后一键备份/恢复。
 * - WebDAV 密码经 AndroidKeyStore 的 AES/GCM 加密后存入偏好设置。
 * 所有网络与文件操作都是阻塞式的，调用方必须放在后台线程。
 */
public final class CloudBackup {
    static final String KEY_URL = "webdav_url";
    static final String KEY_USER = "webdav_user";
    static final String KEY_PASS = "webdav_pass_enc";
    static final String KEY_AUTO = "webdav_auto";
    static final String KEY_LAST = "cloud_last_backup";
    static final String KEY_TREE = "cloud_tree_uri";

    static final String BACKUP_NAME = "monet-notes-backup.zip";
    private static final String DAV_SUB = "monet-notes";
    private static final long MAX_JSON_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ATT_BYTES = 200L * 1024 * 1024;

    private CloudBackup() { }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 配置 ----------------

    static String getUrl(Context ctx) {
        String u = prefs(ctx).getString(KEY_URL, "");
        return u == null ? "" : u.trim();
    }

    static String getUser(Context ctx) {
        String u = prefs(ctx).getString(KEY_USER, "");
        return u == null ? "" : u.trim();
    }

    static boolean davConfigured(Context ctx) {
        return !getUrl(ctx).isEmpty();
    }

    static void saveDavConfig(Context ctx, String url, String user, String pass) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        prefs(ctx).edit()
                .putString(KEY_URL, u)
                .putString(KEY_USER, user == null ? "" : user.trim())
                .putString(KEY_PASS, encrypt(pass == null ? "" : pass))
                .apply();
    }

    static String getPass(Context ctx) {
        String s = prefs(ctx).getString(KEY_PASS, "");
        return decrypt(s == null ? "" : s);
    }

    private static void markBackedUp(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
    }

    // ---------------- 密码加密（AndroidKeyStore AES/GCM） ----------------

    private static SecretKey aesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("monet_webdav_key")) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("monet_webdav_key",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return kg.generateKey();
        }
        return (SecretKey) ks.getKey("monet_webdav_key", null);
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] both = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, both, 0, iv.length);
            System.arraycopy(ct, 0, both, iv.length, ct.length);
            return "v1:" + Base64.encodeToString(both, Base64.NO_WRAP);
        } catch (Exception e) {
            // 极少数机型 Keystore 不可用时退回原样存（仍在应用私有目录内）
            return "raw:" + plain;
        }
    }

    private static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            if (stored.startsWith("raw:")) return stored.substring(4);
            if (!stored.startsWith("v1:")) return stored;
            byte[] both = Base64.decode(stored.substring(3), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(both, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(both, 12, both.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 备份内容（完整 ZIP） ----------------

    static void writeBackupZip(Context ctx, OutputStream out) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(out);
        JSONArray arr = new JSONArray();
        for (Note n : NoteStore.load(ctx)) arr.put(n.toJson());
        zos.putNextEntry(new ZipEntry("notes.json"));
        zos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        byte[] buf = new byte[8192];
        Set<String> seen = new HashSet<>();
        for (Note n : NoteStore.load(ctx)) {
            for (Note.Attachment a : n.attachments) {
                if (a.file == null || !seen.add(a.file)) continue;
                File f = Attachments.fileOf(ctx, a);
                if (!f.exists()) continue;
                zos.putNextEntry(new ZipEntry("attachments/" + a.file));
                try (FileInputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
        zos.finish();
        zos.flush();
    }

    static final class Parsed {
        final List<Note> notes;
        final boolean withAttachments;
        Parsed(List<Note> notes, boolean withAttachments) {
            this.notes = notes;
            this.withAttachments = withAttachments;
        }
    }

    /** 读取备份：ZIP（含附件，会把附件文件写回应用目录）或旧版纯 JSON 都可。 */
    static Parsed readBackup(Context ctx, InputStream in) throws Exception {
        PushbackInputStream pin = new PushbackInputStream(in, 4);
        byte[] magic = new byte[4];
        int got = 0;
        while (got < 4) {
            int r = pin.read(magic, got, 4 - got);
            if (r < 0) break;
            got += r;
        }
        if (got > 0) pin.unread(magic, 0, got);
        boolean isZip = got >= 2 && magic[0] == 'P' && magic[1] == 'K';
        if (!isZip) {
            byte[] all = readAll(pin, MAX_JSON_BYTES);
            return new Parsed(parseNotes(new String(all, StandardCharsets.UTF_8)), false);
        }
        String notesJson = null;
        boolean att = false;
        ZipInputStream zis = new ZipInputStream(pin);
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            if ("notes.json".equals(name)) {
                notesJson = new String(readAll(zis, MAX_JSON_BYTES), StandardCharsets.UTF_8);
            } else if (name.startsWith("attachments/")) {
                String fn = name.substring("attachments/".length());
                if (fn.isEmpty() || fn.contains("/") || fn.contains("..")) continue;
                File outFile = new File(Attachments.dir(ctx), fn);
                long written = 0;
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int r;
                    while ((r = zis.read(buf)) > 0) {
                        written += r;
                        if (written > MAX_ATT_BYTES) {
                            fos.close();
                            outFile.delete();
                            throw new Exception(Lang.t("文件过大"));
                        }
                        fos.write(buf, 0, r);
                    }
                }
                att = true;
            }
        }
        if (notesJson == null) throw new Exception(Lang.t("备份文件无效，导入取消"));
        return new Parsed(parseNotes(notesJson), att);
    }

    private static List<Note> parseNotes(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Note> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(Note.fromJson(arr.getJSONObject(i)));
        return out;
    }

    private static byte[] readAll(InputStream in, long cap) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            baos.write(buf, 0, r);
            if (baos.size() > cap) throw new Exception(Lang.t("文件过大"));
        }
        return baos.toByteArray();
    }

    // ---------------- WebDAV ----------------

    static final class DavException extends Exception {
        final int code;
        DavException(int code, String msg) { super(msg); this.code = code; }
    }

    private static HttpURLConnection davOpen(Context ctx, String path, String method) throws Exception {
        URL u = new URL(getUrl(ctx) + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "MonetNotes");
        String user = getUser(ctx);
        if (!user.isEmpty()) {
            String auth = "Basic " + Base64.encodeToString(
                    (user + ":" + getPass(ctx)).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            c.setRequestProperty("Authorization", auth);
        }
        return c;
    }

    /** 测试连接：GET 服务器地址，能到达且鉴权通过即算成功（405 等说明已到达服务器）。 */
    static void davTest(Context ctx) throws Exception {
        davTest(getUrl(ctx), getUser(ctx), getPass(ctx));
    }

    static void davTest(String url, String user, String pass) throws Exception {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "MonetNotes");
        if (user != null && !user.isEmpty()) {
            String auth = "Basic " + Base64.encodeToString(
                    (user + ":" + (pass == null ? "" : pass)).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            c.setRequestProperty("Authorization", auth);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 401 || code == 403) throw new DavException(code, "HTTP " + code);
        if (code >= 500) throw new DavException(code, "HTTP " + code);
    }

    static void davBackup(Context ctx) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "cloud-backup.zip");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            writeBackupZip(ctx, fos);
        }
        // 优先放进 monet-notes 子文件夹：先尝试建夹（建不了不拦截，PUT 失败再退回根目录）
        boolean subReady = false;
        try {
            HttpURLConnection mk = davOpen(ctx, "/" + DAV_SUB, "MKCOL");
            int mc = mk.getResponseCode();
            mk.disconnect();
            if (mc == 401 || mc == 403) throw new DavException(mc, "HTTP " + mc);
            subReady = mc < 400 || mc == 405;
        } catch (DavException de) {
            throw de;
        } catch (Exception ignored) {
            // 个别服务器/框架不支持 MKCOL 方法字符串时跳过建夹，走根目录回退
        }
        if (subReady) {
            try {
                davPut(ctx, "/" + DAV_SUB + "/" + BACKUP_NAME, tmp);
                markBackedUp(ctx);
                tmp.delete();
                return;
            } catch (DavException de) {
                if (de.code != 404 && de.code != 409) throw de;
            }
        }
        davPut(ctx, "/" + BACKUP_NAME, tmp);
        markBackedUp(ctx);
        tmp.delete();
    }

    private static void davPut(Context ctx, String path, File file) throws Exception {
        HttpURLConnection c = davOpen(ctx, path, "PUT");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(file.length());
        c.setRequestProperty("Content-Type", "application/zip");
        try (OutputStream out = c.getOutputStream(); FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code < 200 || code >= 300) throw new DavException(code, "HTTP " + code);
    }

    static Parsed davRestore(Context ctx) throws Exception {
        String[] paths = {"/" + DAV_SUB + "/" + BACKUP_NAME, "/" + BACKUP_NAME};
        for (String p : paths) {
            HttpURLConnection c = davOpen(ctx, p, "GET");
            int code = c.getResponseCode();
            if (code == 404) { c.disconnect(); continue; }
            if (code < 200 || code >= 300) { c.disconnect(); throw new DavException(code, "HTTP " + code); }
            try (InputStream in = c.getInputStream()) {
                return readBackup(ctx, in);
            } finally {
                c.disconnect();
            }
        }
        throw new DavException(404, Lang.t("云端还没有备份"));
    }

    // ---------------- 云盘文件夹（SAF 目录树） ----------------

    static String getTree(Context ctx) {
        String s = prefs(ctx).getString(KEY_TREE, "");
        return s == null ? "" : s;
    }

    private static Uri findChild(Context ctx, Uri treeUri, String name) {
        ContentResolver cr = ctx.getContentResolver();
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
        try (Cursor c = cr.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static void safBackup(Context ctx, Uri treeUri) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Uri target = findChild(ctx, treeUri, BACKUP_NAME);
        if (target == null) {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
            target = DocumentsContract.createDocument(cr, parent, "application/zip", BACKUP_NAME);
            if (target == null) throw new Exception(Lang.t("无法读取文件"));
        }
        try (OutputStream out = cr.openOutputStream(target, "wt")) {
            if (out == null) throw new Exception(Lang.t("无法读取文件"));
            writeBackupZip(ctx, out);
        }
        markBackedUp(ctx);
    }

    static Parsed safRestore(Context ctx, Uri treeUri) throws Exception {
        Uri target = findChild(ctx, treeUri, BACKUP_NAME);
        if (target == null) throw new Exception(Lang.t("云盘文件夹里还没有备份"));
        try (InputStream in = ctx.getContentResolver().openInputStream(target)) {
            if (in == null) throw new Exception(Lang.t("无法读取文件"));
            return readBackup(ctx, in);
        }
    }

    // ---------------- 自动备份调度（每天一次） ----------------

    static android.app.PendingIntent autoIntent(Context ctx) {
        android.content.Intent i = new android.content.Intent(ctx, AutoBackupReceiver.class);
        return android.app.PendingIntent.getBroadcast(ctx, 7701, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAuto(Context ctx) {
        android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        android.app.PendingIntent pi = autoIntent(ctx);
        if (!prefs(ctx).getBoolean(KEY_AUTO, false) || !davConfigured(ctx)) {
            am.cancel(pi);
            return;
        }
        // 下一个凌晨 3 点，每天一次（非精确闹钟，省电且不需特殊权限）
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 3);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                android.app.AlarmManager.INTERVAL_DAY, pi);
    }
}
