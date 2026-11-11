package com.muse.monetnotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Note {
    public String id;
    public String title = "";
    public String content = "";
    public boolean checklist = false;
    public List<CheckItem> items = new ArrayList<>();
    public long createdAt;
    public long updatedAt;
    public boolean pinned = false;
    public boolean favorite = false;
    public boolean archived = false;
    public boolean trashed = false;
    public List<String> tags = new ArrayList<>();
    public int colorIndex = 0; // 0 = theme default, 1..6 = preset tint
    public int customColor = 0; // ARGB, 0 = unused; when set, takes precedence over colorIndex
    public String category = ""; // empty = uncategorized
    public List<Attachment> attachments = new ArrayList<>();
    public long reminderAt = 0;
    public long order = 0; // manual sort position (v1.6)

    public static class CheckItem {
        public String text;
        public boolean done;
        public CheckItem(String text, boolean done) { this.text = text; this.done = done; }
    }

    public static class Attachment {
        public String id;
        public String name;
        public String mime;
        public String file; // filename inside files/attachments/
        public long size;
        public Attachment(String id, String name, String mime, String file, long size) {
            this.id = id; this.name = name; this.mime = mime; this.file = file; this.size = size;
        }
        public boolean isImage() { return mime != null && mime.startsWith("image/"); }
    }

    public Note() {
        this.id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public int doneCount() {
        int n = 0;
        for (CheckItem it : items) if (it.done) n++;
        return n;
    }

    public String plainText() {
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append(title).append("\n");
        if (checklist) {
            for (CheckItem it : items) sb.append(it.done ? "☑ " : "☐ ").append(it.text).append("\n");
        } else {
            sb.append(content);
        }
        return sb.toString().trim();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("content", content);
        o.put("checklist", checklist);
        JSONArray arr = new JSONArray();
        for (CheckItem it : items) {
            JSONObject io = new JSONObject();
            io.put("text", it.text);
            io.put("done", it.done);
            arr.put(io);
        }
        o.put("items", arr);
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        o.put("pinned", pinned);
        o.put("favorite", favorite);
        o.put("archived", archived);
        o.put("trashed", trashed);
        JSONArray tg = new JSONArray();
        for (String t : tags) tg.put(t);
        o.put("tags", tg);
        o.put("colorIndex", colorIndex);
        o.put("customColor", customColor);
        o.put("category", category);
        JSONArray at = new JSONArray();
        for (Attachment a : attachments) {
            JSONObject ao = new JSONObject();
            ao.put("id", a.id);
            ao.put("name", a.name);
            ao.put("mime", a.mime);
            ao.put("file", a.file);
            ao.put("size", a.size);
            at.put(ao);
        }
        o.put("attachments", at);
        o.put("reminderAt", reminderAt);
        o.put("order", order);
        return o;
    }

    public static Note fromJson(JSONObject o) throws JSONException {
        Note n = new Note();
        n.id = o.optString("id", n.id);
        n.title = o.optString("title", "");
        n.content = o.optString("content", "");
        n.checklist = o.optBoolean("checklist", false);
        JSONArray arr = o.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject io = arr.getJSONObject(i);
                n.items.add(new CheckItem(io.optString("text", ""), io.optBoolean("done", false)));
            }
        }
        n.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        n.updatedAt = o.optLong("updatedAt", n.createdAt);
        n.pinned = o.optBoolean("pinned", false);
        n.favorite = o.optBoolean("favorite", false);
        n.archived = o.optBoolean("archived", false);
        n.trashed = o.optBoolean("trashed", false);
        JSONArray tg = o.optJSONArray("tags");
        if (tg != null) for (int i = 0; i < tg.length(); i++) n.tags.add(tg.getString(i));
        n.colorIndex = o.optInt("colorIndex", 0);
        n.customColor = o.optInt("customColor", 0);
        n.category = o.optString("category", "");
        JSONArray at = o.optJSONArray("attachments");
        if (at != null) {
            for (int i = 0; i < at.length(); i++) {
                JSONObject ao = at.getJSONObject(i);
                n.attachments.add(new Attachment(
                        ao.optString("id", UUID.randomUUID().toString()),
                        ao.optString("name", "附件"),
                        ao.optString("mime", "application/octet-stream"),
                        ao.optString("file", ""),
                        ao.optLong("size", 0)));
            }
        }
        n.reminderAt = o.optLong("reminderAt", 0);
        n.order = o.optLong("order", 0);
        return n;
    }

    /** 用另一份笔记的全部字段覆盖本笔记（撤销/恢复时使用，保持对象引用不变）。 */
    public void copyFrom(Note o) {
        this.title = o.title;
        this.content = o.content;
        this.checklist = o.checklist;
        this.items = o.items;
        this.createdAt = o.createdAt;
        this.updatedAt = o.updatedAt;
        this.pinned = o.pinned;
        this.favorite = o.favorite;
        this.archived = o.archived;
        this.trashed = o.trashed;
        this.tags = o.tags;
        this.colorIndex = o.colorIndex;
        this.customColor = o.customColor;
        this.category = o.category;
        this.attachments = o.attachments;
        this.reminderAt = o.reminderAt;
        this.order = o.order;
    }

    public String toJsonString() {
        try { return toJson().toString(); } catch (JSONException e) { return ""; }
    }

    public static Note fromJsonString(String s) {
        try { return fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }
}
