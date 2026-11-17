package com.muse.monetnotes;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 笔记历史版本：每篇笔记一个文件（files/history/<笔记id>.json），存载荷快照（新→旧）。
 * NoteStore 每次落盘后由 onSaved 维护版本链：
 *  - 首次见到一篇笔记，先把当前状态存为初始版本（之后的改动才有得回退）；
 *  - 内容指纹与最新版本相同 → 跳过；最新版本未满 3 分钟 → 合并替换（时间戳锚定不滑动，
 *    保证长编辑会话里约每 3 分钟留一个版本）；否则追加新版本；最多保留 50 个。
 * 加密笔记只存密文快照（salt+data），绝不落明文；附件与分类等元数据不在版本范围内。
 */
public class NoteHistory {
    public static final long COALESCE_MS = 3 * 60 * 1000L;
    public static final int MAX_VERSIONS = 50;
    private static final long ORPHAN_TTL_MS = 24 * 3600 * 1000L;

    public static final int SKIP = 0;
    public static final int APPEND = 1;
    public static final int REPLACE = 2;

    /** 恢复操作置位（按笔记 id）：下一次 onSaved 对该笔记强制追加（否则合并窗口会把恢复前的快照顶掉）。 */
    private static String forceAppendNoteId = null;
    private static boolean orphansCleaned = false;

    public static class Version {
        public long t;
        public String fp = "";
        public boolean enc = false;
        public JSONObject payload; // 普通版本：载荷 JSON
        public String salt = "";   // 加密版本：与 data 一起构成完整密文状态
        public String data = "";

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("fp", fp);
            o.put("enc", enc);
            if (enc) {
                o.put("salt", salt);
                o.put("data", data);
            } else if (payload != null) {
                o.put("p", payload);
            }
            return o;
        }

        static Version fromJson(JSONObject o) {
            Version v = new Version();
            v.t = o.optLong("t", 0);
            v.fp = o.optString("fp", "");
            v.enc = o.optBoolean("enc", false);
            if (v.enc) {
                v.salt = o.optString("salt", "");
                v.data = o.optString("data", "");
            } else {
                v.payload = o.optJSONObject("p");
            }
            return v;
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File file(Context ctx, String noteId) {
        return new File(dir(ctx), noteId + ".json");
    }

    public static synchronized List<Version> load(Context ctx, String noteId) {
        List<Version> out = new ArrayList<>();
        try {
            File f = file(ctx, noteId);
            if (!f.exists()) return out;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(s).optJSONArray("versions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) out.add(Version.fromJson(o));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static synchronized void persist(Context ctx, String noteId, List<Version> vs) {
        try {
            JSONArray arr = new JSONArray();
            for (Version v : vs) arr.put(v.toJson());
            JSONObject root = new JSONObject();
            root.put("versions", arr);
            Files.write(file(ctx, noteId).toPath(),
                    root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 纯决策（可单测）：版本链、待存指纹、当前时间 → 跳过/追加/合并替换。 */
    public static int decide(List<Version> vs, String fp, long now) {
        if (!vs.isEmpty() && vs.get(0).fp.equals(fp)) return SKIP;
        if (!vs.isEmpty() && now - vs.get(0).t < COALESCE_MS) return REPLACE;
        return APPEND;
    }

    /** 某个版本是否就是笔记的当前状态（列表里标「当前」用）。 */
    public static boolean isCurrent(Note n, Version v) {
        return v != null && v.fp.equals(fpOf(n));
    }

    private static String fpOf(Note n) {
        if (n.encrypted) return "enc:" + sha(n.encData == null ? "" : n.encData);
        return "txt:" + sha(n.payloadFingerprint());
    }

    private static Version snapshotOf(Note n, long t) {
        Version v = new Version();
        v.t = t;
        v.fp = fpOf(n);
        if (n.encrypted) {
            v.enc = true;
            v.salt = n.encSalt;
            v.data = n.encData;
        } else {
            try {
                v.payload = n.payloadJson();
            } catch (Exception e) {
                return null;
            }
        }
        return v;
    }

    private static boolean isEmptyState(Note n) {
        if (n.encrypted) return n.encData == null || n.encData.isEmpty();
        return n.title.isEmpty() && n.content.isEmpty() && n.items.isEmpty();
    }

    /** NoteStore 每次落盘成功后调用：为每篇笔记维护版本链。 */
    public static synchronized void onSaved(Context ctx, List<Note> notes) {
        if (ctx == null || notes == null) return;
        try {
            long now = System.currentTimeMillis();
            for (Note n : notes) {
                if (isEmptyState(n)) continue;
                List<Version> vs = load(ctx, n.id);
                if (vs.isEmpty()) {
                    Version seed = snapshotOf(n, n.updatedAt > 0 ? n.updatedAt : now);
                    if (seed != null) {
                        vs.add(seed);
                        persist(ctx, n.id, vs);
                    }
                    continue;
                }
                String fp = fpOf(n);
                int d = decide(vs, fp, now);
                if (n.id.equals(forceAppendNoteId)) {
                    if (d == REPLACE) d = APPEND;
                    forceAppendNoteId = null;
                }
                if (d == SKIP) continue;
                Version snap = snapshotOf(n, now);
                if (snap == null) continue;
                if (d == REPLACE) {
                    snap.t = vs.get(0).t; // 时间戳锚定：合并窗口不滑动
                    vs.set(0, snap);
                } else {
                    vs.add(0, snap);
                }
                while (vs.size() > MAX_VERSIONS) vs.remove(vs.size() - 1);
                persist(ctx, n.id, vs);
            }
            cleanupOrphans(ctx, notes);
        } catch (Exception ignored) {
        }
    }

    /** 恢复前调用：把当前状态强制存档（只按指纹去重），保证恢复后还能再回到当前。 */
    public static synchronized void snapshotCurrent(Context ctx, Note n) {
        if (n == null || isEmptyState(n)) return;
        try {
            List<Version> vs = load(ctx, n.id);
            String fp = fpOf(n);
            if (!vs.isEmpty() && vs.get(0).fp.equals(fp)) return;
            Version snap = snapshotOf(n, System.currentTimeMillis());
            if (snap == null) return;
            vs.add(0, snap);
            while (vs.size() > MAX_VERSIONS) vs.remove(vs.size() - 1);
            persist(ctx, n.id, vs);
        } catch (Exception ignored) {
        }
    }

    /** 恢复流程在改笔记前调用：让紧接着的保存把恢复后的状态追加为新版本，而非合并顶掉恢复前快照。 */
    public static void expectAppendOnNextSave(String noteId) {
        forceAppendNoteId = noteId;
    }

    /** 清理已彻底删除笔记的历史文件（超过 24 小时才删，给主页的撤销留出窗口）；每进程只跑一次。 */
    private static void cleanupOrphans(Context ctx, List<Note> notes) {
        if (orphansCleaned) return;
        orphansCleaned = true;
        try {
            Set<String> keep = new HashSet<>();
            for (Note n : notes) keep.add(n.id + ".json");
            File[] fs = dir(ctx).listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                if (!keep.contains(f.getName()) && now - f.lastModified() > ORPHAN_TTL_MS) {
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(
                    md.digest(s.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
