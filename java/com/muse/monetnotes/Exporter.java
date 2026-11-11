package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记导出（v1.44）：Markdown / 纯文本 / HTML / PDF / 长图(PNG) 五种格式。
 * 文件先生成到 files/exports/，再由用户选择「分享」（经 AttachProvider 的 exp 路径发 content://）
 * 或「保存到…」（SAF 另存）。PDF 与长图共用 compose() 的富文本排版（标题+元信息+Markdown 渲染正文）。
 */
public final class Exporter {
    static final int REQ_SAVE = 9042;
    static final int REQ_SAVE_TREE = 9043;

    private static final int F_MD = 0, F_TXT = 1, F_HTML = 2, F_PDF = 3, F_PNG = 4;
    private static final String[] EXTS = {".md", ".txt", ".html", ".pdf", ".png"};
    private static final String[] MIMES = {"text/markdown", "text/plain", "text/html", "application/pdf", "image/png"};

    private static File pendingFile;
    private static List<File> pendingFiles;

    private Exporter() { }

    static File dir(android.content.Context ctx) {
        File d = new File(ctx.getFilesDir(), "exports");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static Uri uriOf(File f) {
        return new Uri.Builder().scheme("content").authority(Attachments.AUTHORITY)
                .appendPath("exp").appendPath(f.getName()).build();
    }

    // ---------------- UI 流程 ----------------

    static void showExport(final Activity act, final Note note, final Palette pal) {
        final String[] labels = {
                "Markdown (.md)",
                Lang.t("纯文本") + " (.txt)",
                Lang.t("HTML 网页") + " (.html)",
                Lang.t("PDF 文档") + " (.pdf)",
                Lang.t("长图") + " (.png)"};
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.t("导出笔记"))
                .setItems(labels, (d, which) -> {
                    if (which == F_PNG) startPngExport(act, note, pal);
                    else generate(act, note, pal, which);
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(act, dlg);
    }

    /** 延迟显示的加载弹窗：任务超过一小段时间没结束才出现，避免小文件导出时弹窗一闪而过。 */
    private static final class Loading {
        private AlertDialog box;
        private boolean done;

        Loading(final Activity act, final Palette pal) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!done && !act.isFinishing()) box = buildLoadingDialog(act, pal);
            }, 350);
        }

        void finish() {
            done = true;
            if (box != null) { box.dismiss(); box = null; }
        }
    }

    private static void generate(final Activity act, final Note note, final Palette pal, final int fmt) {
        final Loading loading = new Loading(act, pal);
        new Thread(() -> {
            File f = null;
            Exception err = null;
            try {
                f = generateFile(act, note, pal, fmt);
            } catch (Exception e) {
                err = e;
            }
            final File ff = f;
            final Exception ee = err;
            act.runOnUiThread(() -> {
                loading.finish();
                if (act.isFinishing()) return;
                if (ee == null && ff != null) {
                    showReady(act, ff, MIMES[fmt]);
                } else {
                    Toast.makeText(act, Lang.f("导出失败：%1$s",
                            ee == null ? "" : String.valueOf(ee.getMessage())), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ---------------- 长图导出流程 ----------------

    /** 选了长图先在原字号下量整篇高度：不长直接出单图；过长则弹窗让用户选多张连续图或缩字号单图。 */
    private static void startPngExport(final Activity act, final Note note, final Palette pal) {
        final Loading loading = new Loading(act, pal);
        new Thread(() -> {
            int totalH = 0, slices = 1;
            Exception err = null;
            try {
                StaticLayout layout = buildPngLayout(note, pal, 1f);
                totalH = layout.getHeight() + PNG_PAD * 2;
                slices = sliceCount(layout.getHeight());
            } catch (Exception e) {
                err = e;
            }
            final int fTotalH = totalH, fSlices = slices;
            final Exception ee = err;
            act.runOnUiThread(() -> {
                loading.finish();
                if (act.isFinishing()) return;
                if (ee != null) {
                    Toast.makeText(act, Lang.f("导出失败：%1$s", String.valueOf(ee.getMessage())),
                            Toast.LENGTH_LONG).show();
                } else if (fTotalH <= PNG_SINGLE_LIMIT) {
                    runPng(act, note, pal, false);
                } else {
                    showPngChoice(act, note, pal, fSlices);
                }
            });
        }).start();
    }

    private static void showPngChoice(final Activity act, final Note note, final Palette pal, final int slices) {
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.t("导出长图"))
                .setMessage(Lang.f("这篇笔记内容较长，一张图放不下。\n\n可导出为 %1$d 张连续图片（保持原字号，文件名带序号），或缩成一张长图（字号会自动缩小）。", slices))
                .setPositiveButton(Lang.f("导出为 %1$d 张图片", slices), (d, w) -> runPng(act, note, pal, true))
                .setNeutralButton(Lang.t("一张长图（缩字号）"), (d, w) -> runPng(act, note, pal, false))
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(act, dlg);
    }

    private static void runPng(final Activity act, final Note note, final Palette pal, final boolean multi) {
        final Loading loading = new Loading(act, pal);
        new Thread(() -> {
            final List<File> files = new ArrayList<>();
            Exception err = null;
            try {
                if (multi) writePngSlices(act, note, pal, files);
                else files.add(generateFile(act, note, pal, F_PNG));
            } catch (Exception e) {
                err = e;
            }
            final Exception ee = err;
            act.runOnUiThread(() -> {
                loading.finish();
                if (act.isFinishing()) return;
                if (ee != null || files.isEmpty()) {
                    Toast.makeText(act, Lang.f("导出失败：%1$s",
                            ee == null ? "" : String.valueOf(ee.getMessage())), Toast.LENGTH_LONG).show();
                } else if (multi) {
                    showReadyMulti(act, files, baseName(note));
                } else {
                    showReady(act, files.get(0), MIMES[F_PNG]);
                }
            });
        }).start();
    }

    private static void showReadyMulti(final Activity act, final List<File> files, final String base) {
        long total = 0;
        for (File f : files) total += f.length();
        final String[] actions = {
                Lang.t("分享整组图片"), Lang.t("打包成压缩包"), Lang.t("保存到文件夹…")};
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.f("导出为 %1$d 张图片", files.size()) + " · " + Attachments.formatSize(total))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        try {
                            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                            i.setType("image/png");
                            ArrayList<Uri> uris = new ArrayList<>();
                            for (File f : files) uris.add(uriOf(f));
                            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            act.startActivity(Intent.createChooser(i, Lang.t("分享笔记")));
                        } catch (Exception e) {
                            Toast.makeText(act, Lang.f("导出失败：%1$s", String.valueOf(e.getMessage())),
                                    Toast.LENGTH_LONG).show();
                        }
                    } else if (which == 1) {
                        // 打包成一个 ZIP 后走单文件流程（分享 / 保存到…）
                        final Loading loading = new Loading(act, Palette.of(act));
                        new Thread(() -> {
                            File zip = null;
                            Exception err = null;
                            try {
                                zip = zipFiles(act, files, base + ".zip");
                            } catch (Exception e) {
                                err = e;
                            }
                            final File zf = zip;
                            final Exception ee = err;
                            act.runOnUiThread(() -> {
                                loading.finish();
                                if (act.isFinishing()) return;
                                if (zf != null) {
                                    showReady(act, zf, "application/zip");
                                } else {
                                    Toast.makeText(act, Lang.f("导出失败：%1$s",
                                            ee == null ? "" : String.valueOf(ee.getMessage())), Toast.LENGTH_LONG).show();
                                }
                            });
                        }).start();
                    } else if (which == 2) {
                        pendingFiles = files;
                        act.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_TREE);
                    }
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(act, dlg);
    }

    /** 把多个文件打包成一个 ZIP（PNG 已是压缩格式，只打包不再压缩，速度快）。 */
    private static File zipFiles(android.content.Context ctx, List<File> files, String zipName) throws Exception {
        File zip = new File(dir(ctx), zipName);
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
            zos.setLevel(0);
            byte[] buf = new byte[8192];
            for (File f : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    /** 把多个导出文件逐个写入用户选定的文件夹（SAF 目录树 + DocumentsContract，无需第三方库）。 */
    private static void saveToFolder(final Activity act, Uri treeUri, final List<File> files) {
        new Thread(() -> {
            int ok = 0;
            try {
                String docId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                ContentResolver cr = act.getContentResolver();
                for (File f : files) {
                    Uri child = DocumentsContract.createDocument(cr, parent, "image/png", f.getName());
                    if (child == null) continue;
                    try (OutputStream out = cr.openOutputStream(child);
                         FileInputStream in = new FileInputStream(f)) {
                        if (out == null) continue;
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        ok++;
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            final int okCount = ok;
            act.runOnUiThread(() -> {
                if (okCount > 0) {
                    Toast.makeText(act, Lang.f("已保存 %1$d 张图片", okCount), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(act, Lang.f("导出失败：%1$s", "0/" + files.size()), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** 主题化加载弹窗：转圈 + 文案，跟随当前主题与液态玻璃风格。 */
    private static AlertDialog buildLoadingDialog(Activity act, Palette pal) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Ui.dp(act, 24);
        box.setPadding(padH, Ui.dp(act, 20), padH, Ui.dp(act, 20));
        box.addView(new ProgressBar(act));
        TextView label = Ui.text(act, Lang.t("正在生成导出文件…"), 15, pal.textPrimary, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(act, 16), 0, 0, 0);
        box.addView(label, lp);
        AlertDialog d = new AlertDialog.Builder(act).setView(box).setCancelable(false).show();
        Ui.styleDialog(act, d);
        return d;
    }

    private static void showReady(final Activity act, final File f, final String mime) {
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.t("导出笔记"))
                .setMessage(f.getName() + "  ·  " + Attachments.formatSize(f.length()))
                .setPositiveButton(Lang.t("分享"), (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uriOf(f));
                        i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        act.startActivity(Intent.createChooser(i, Lang.t("分享笔记")));
                    } catch (Exception e) {
                        Toast.makeText(act, Lang.f("导出失败：%1$s", String.valueOf(e.getMessage())),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton(Lang.t("保存到…"), (d, w) -> {
                    pendingFile = f;
                    Intent c = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    c.addCategory(Intent.CATEGORY_OPENABLE);
                    c.setType(mime);
                    c.putExtra(Intent.EXTRA_TITLE, f.getName());
                    act.startActivityForResult(c, REQ_SAVE);
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(act, dlg);
    }

    /** Activity 的 onActivityResult 里调用一行即可；返回 true 表示已消费。 */
    static boolean handleSaveResult(Activity act, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SAVE_TREE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingFiles != null) {
                saveToFolder(act, data.getData(), pendingFiles);
            }
            pendingFiles = null;
            return true;
        }
        if (requestCode != REQ_SAVE) return false;
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && pendingFile != null) {
            try (OutputStream out = act.getContentResolver().openOutputStream(data.getData());
                 FileInputStream in = new FileInputStream(pendingFile)) {
                if (out != null) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                Toast.makeText(act, Lang.t("已保存"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(act, Lang.f("导出失败：%1$s", String.valueOf(e.getMessage())),
                        Toast.LENGTH_LONG).show();
            }
        }
        pendingFile = null;
        return true;
    }

    // ---------------- 生成入口 ----------------

    static File generateFile(android.content.Context ctx, Note note, Palette pal, int fmt) throws Exception {
        File f = new File(dir(ctx), baseName(note) + EXTS[fmt]);
        switch (fmt) {
            case F_MD: writeText(f, toMarkdown(note)); break;
            case F_TXT: writeText(f, toText(note)); break;
            case F_HTML: writeText(f, toHtml(note)); break;
            case F_PDF: writePdf(f, note, pal); break;
            case F_PNG: writePng(f, note, pal); break;
            default: throw new IllegalArgumentException("fmt " + fmt);
        }
        return f;
    }

    private static void writeText(File f, String s) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static String baseName(Note n) {
        String t = n.title == null ? "" : n.title.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim();
        if (t.isEmpty()) t = Lang.t("无标题笔记");
        return t;
    }

    static String metaLine(Note n) {
        String s = new SimpleDateFormat(Lang.t("yyyy年M月d日 HH:mm"), Locale.US).format(new Date(n.createdAt));
        if (n.category != null && !n.category.isEmpty()) s += "   📁 " + n.category;
        if (!n.tags.isEmpty()) s += "   #" + String.join("  #", n.tags);
        return s;
    }

    // ---------------- 文本格式 ----------------

    static String toMarkdown(Note n) {
        StringBuilder sb = new StringBuilder();
        String title = n.title == null ? "" : n.title.trim();
        if (!title.isEmpty()) sb.append("# ").append(title).append("\n\n");
        if (n.checklist) {
            for (Note.CheckItem it : n.items)
                sb.append(it.done ? "- [x] " : "- [ ] ").append(it.text == null ? "" : it.text).append('\n');
        } else if (n.content != null) {
            sb.append(n.content);
        }
        if (!n.attachments.isEmpty()) {
            sb.append("\n\n---\n\n").append(Lang.t("附件：")).append("\n\n");
            for (Note.Attachment a : n.attachments)
                sb.append("- 📎 ").append(a.name).append(" (").append(Attachments.formatSize(a.size)).append(")\n");
        }
        return sb.toString().trim() + "\n";
    }

    static String toText(Note n) {
        StringBuilder sb = new StringBuilder(n.plainText());
        if (!n.attachments.isEmpty()) {
            sb.append("\n\n").append(Lang.t("附件：")).append('\n');
            for (Note.Attachment a : n.attachments)
                sb.append("- ").append(a.name).append(" (").append(Attachments.formatSize(a.size)).append(")\n");
        }
        return sb.toString().trim() + "\n";
    }

    // ---------------- HTML ----------------

    private static final Pattern P_HEAD = Pattern.compile("^(#{1,6})\\s+");
    private static final Pattern P_QUOTE = Pattern.compile("^>\\s?");
    private static final Pattern P_CHECK = Pattern.compile("^(\\s*[-*+]\\s\\[( |x|X)\\]\\s+)(.*)$");
    private static final Pattern P_BULLET = Pattern.compile("^(\\s*[-*+]\\s+)");
    private static final Pattern P_NUMBER = Pattern.compile("^(\\s*\\d{1,9}[.)]\\s+)");
    private static final Pattern P_DIV = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern P_TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern P_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern P_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__");
    private static final Pattern P_ITALIC = Pattern.compile("\\*([^*\\n]+)\\*|_([^_\\n]+)_");
    private static final Pattern P_STRIKE = Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern P_LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");

    static String toHtml(Note n) {
        String title = n.title == null ? "" : n.title.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"").append(Lang.isEn() ? "en" : "zh-CN").append("\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(esc(title.isEmpty() ? Lang.t("无标题笔记") : title)).append("</title>\n");
        sb.append("<style>\n")
          .append("body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;max-width:760px;margin:0 auto;padding:28px 20px 48px;color:#1c1b1f;background:#fff;line-height:1.75;font-size:16px}\n")
          .append("h1.t{font-size:26px;margin:0 0 6px;line-height:1.35}\n")
          .append(".meta{color:#8a8694;font-size:13px;margin:0 0 22px}\n")
          .append("h1{font-size:23px}h2{font-size:20px}h3{font-size:18px}h4,h5,h6{font-size:16px}\n")
          .append("p{margin:8px 0}\n")
          .append("code{background:#f0eef4;padding:2px 6px;border-radius:6px;font-family:ui-monospace,monospace;font-size:.92em}\n")
          .append("pre{background:#f6f4fa;padding:14px 16px;border-radius:10px;overflow-x:auto}\n")
          .append("pre code{background:none;padding:0}\n")
          .append("blockquote{border-left:3px solid #6750a4;margin:12px 0;padding:2px 14px;color:#666}\n")
          .append("a{color:#6750a4}\n")
          .append("hr{border:none;border-top:1px solid #ddd;margin:24px 0}\n")
          .append("table{border-collapse:collapse;margin:10px 0}\n")
          .append("td,th{border:1px solid #cfcbd8;padding:6px 12px}\n")
          .append("ul,ol{margin:8px 0;padding-left:26px}\n")
          .append("ul.todo{list-style:none;padding-left:4px}\n")
          .append(".done{color:#999;text-decoration:line-through}\n")
          .append("input[type=checkbox]{transform:scale(1.15);margin-right:8px}\n")
          .append(".att{color:#555;font-size:14px}\n")
          .append("</style>\n</head>\n<body>\n");
        if (!title.isEmpty()) sb.append("<h1 class=\"t\">").append(esc(title)).append("</h1>\n");
        sb.append("<p class=\"meta\">").append(esc(metaLine(n))).append("</p>\n");
        sb.append(htmlBody(n));
        if (!n.attachments.isEmpty()) {
            sb.append("<hr>\n<p class=\"att\">").append(esc(Lang.t("附件："))).append("</p>\n<ul class=\"att\">\n");
            for (Note.Attachment a : n.attachments)
                sb.append("<li>📎 ").append(esc(a.name)).append(" (").append(Attachments.formatSize(a.size)).append(")</li>\n");
            sb.append("</ul>\n");
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static String htmlBody(Note n) {
        if (n.checklist) {
            StringBuilder sb = new StringBuilder();
            sb.append("<ul class=\"todo\">\n");
            for (Note.CheckItem it : n.items) {
                sb.append("<li><input type=\"checkbox\" disabled");
                if (it.done) sb.append(" checked");
                sb.append("> <span");
                if (it.done) sb.append(" class=\"done\"");
                sb.append('>').append(inlineHtml(it.text == null ? "" : it.text)).append("</span></li>\n");
            }
            sb.append("</ul>\n");
            return sb.toString();
        }
        HtmlGen g = new HtmlGen();
        String src = n.content == null ? "" : n.content;
        for (String line : src.split("\n", -1)) g.feed(line);
        return g.finish();
    }

    /** 行级 Markdown → HTML 的小状态机：列表/引用/表格按连续行合并，代码块整段转义。 */
    private static final class HtmlGen {
        final StringBuilder sb = new StringBuilder();
        int listKind = 0; // 1 无序 2 有序 3 待办
        final List<String> table = new ArrayList<>();
        final List<String> quote = new ArrayList<>();
        boolean inCode = false;
        final StringBuilder code = new StringBuilder();

        void openList(int kind) {
            if (listKind == kind) return;
            closeList();
            listKind = kind;
            sb.append(kind == 2 ? "<ol>\n" : "<ul>\n");
        }

        void closeList() {
            if (listKind == 0) return;
            sb.append(listKind == 2 ? "</ol>\n" : "</ul>\n");
            listKind = 0;
        }

        void emitQuote() {
            if (quote.isEmpty()) return;
            sb.append("<blockquote>");
            for (int i = 0; i < quote.size(); i++) {
                if (i > 0) sb.append("<br>");
                sb.append(inlineHtml(quote.get(i)));
            }
            sb.append("</blockquote>\n");
            quote.clear();
        }

        void emitTable() {
            if (table.isEmpty()) return;
            sb.append("<table>\n");
            int start = 0;
            boolean header = table.size() > 1 && isTableSep(table.get(1));
            if (header) { emitRow(table.get(0), "th"); start = 2; }
            for (int i = start; i < table.size(); i++) emitRow(table.get(i), "td");
            sb.append("</table>\n");
            table.clear();
        }

        void emitRow(String row, String tag) {
            String r = row.trim();
            if (r.startsWith("|")) r = r.substring(1);
            if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
            sb.append("<tr>");
            for (String cell : r.split("\\|", -1))
                sb.append('<').append(tag).append('>').append(inlineHtml(cell.trim())).append("</").append(tag).append('>');
            sb.append("</tr>\n");
        }

        static boolean isTableSep(String row) {
            String r = row.trim();
            if (r.startsWith("|")) r = r.substring(1);
            if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
            for (String cell : r.split("\\|", -1)) {
                String c = cell.trim();
                if (!c.matches(":?-+:?")) return false;
            }
            return true;
        }

        void flushAll() { emitQuote(); emitTable(); closeList(); }

        void feed(String line) {
            String trim = line.trim();
            if (trim.startsWith("```")) {
                if (inCode) {
                    sb.append("<pre><code>").append(esc(code.toString())).append("</code></pre>\n");
                    code.setLength(0);
                    inCode = false;
                } else {
                    flushAll();
                    inCode = true;
                }
                return;
            }
            if (inCode) { code.append(line).append('\n'); return; }
            if (trim.isEmpty()) { flushAll(); return; }
            Matcher m;
            if ((m = P_HEAD.matcher(line)).find()) {
                flushAll();
                int lvl = m.group(1).length();
                sb.append("<h").append(lvl).append('>').append(inlineHtml(line.substring(m.end())))
                  .append("</h").append(lvl).append(">\n");
            } else if (P_DIV.matcher(line).matches()) {
                flushAll();
                sb.append("<hr>\n");
            } else if ((m = P_CHECK.matcher(line)).find()) {
                emitQuote(); emitTable(); openList(3);
                boolean done = !" ".equals(m.group(2));
                sb.append("<li><input type=\"checkbox\" disabled");
                if (done) sb.append(" checked");
                sb.append("> <span");
                if (done) sb.append(" class=\"done\"");
                sb.append('>').append(inlineHtml(m.group(3))).append("</span></li>\n");
            } else if ((m = P_BULLET.matcher(line)).find()) {
                emitQuote(); emitTable(); openList(1);
                sb.append("<li>").append(inlineHtml(line.substring(m.end(1)))).append("</li>\n");
            } else if ((m = P_NUMBER.matcher(line)).find()) {
                emitQuote(); emitTable(); openList(2);
                sb.append("<li>").append(inlineHtml(line.substring(m.end(1)))).append("</li>\n");
            } else if (P_TABLE_ROW.matcher(line).matches()) {
                emitQuote(); closeList();
                table.add(line);
            } else if ((m = P_QUOTE.matcher(line)).find()) {
                emitTable(); closeList();
                quote.add(line.substring(m.end()));
            } else {
                flushAll();
                sb.append("<p>").append(inlineHtml(line)).append("</p>\n");
            }
        }

        String finish() {
            if (inCode) {
                sb.append("<pre><code>").append(esc(code.toString())).append("</code></pre>\n");
                inCode = false;
            }
            flushAll();
            return sb.toString();
        }
    }

    /** 行内语法 → HTML：按最早出现的语法逐段处理，粗/斜/删内部递归，代码段只转义。 */
    private static String inlineHtml(String raw) {
        Pattern[] pats = {P_CODE, P_BOLD, P_STRIKE, P_LINK, P_ITALIC};
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < raw.length()) {
            Matcher best = null;
            int kind = -1;
            for (int k = 0; k < pats.length; k++) {
                Matcher mm = pats[k].matcher(raw);
                if (mm.find(pos) && (best == null || mm.start() < best.start())) { best = mm; kind = k; }
            }
            if (best == null) { out.append(esc(raw.substring(pos))); break; }
            out.append(esc(raw.substring(pos, best.start())));
            String inner = best.group(1) != null ? best.group(1) : best.group(2);
            switch (kind) {
                case 0: out.append("<code>").append(esc(inner == null ? "" : inner)).append("</code>"); break;
                case 1: out.append("<strong>").append(inlineHtml(inner == null ? "" : inner)).append("</strong>"); break;
                case 2: out.append("<del>").append(inlineHtml(inner == null ? "" : inner)).append("</del>"); break;
                case 3:
                    out.append("<a href=\"").append(escAttr(best.group(2) == null ? "" : best.group(2))).append("\">")
                       .append(inlineHtml(best.group(1) == null ? "" : best.group(1))).append("</a>");
                    break;
                case 4: out.append("<em>").append(inlineHtml(inner == null ? "" : inner)).append("</em>"); break;
                default: break;
            }
            pos = best.end();
        }
        return out.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escAttr(String s) {
        return esc(s).replace("\"", "&quot;");
    }

    // ---------------- 富文本排版（PDF / 长图共用） ----------------

    /** 正文渲染：普通笔记走 Markdown.render；清单笔记逐条拼 ☐/☑，完成项划线变灰。 */
    static CharSequence renderBody(Note n, Palette pal) {
        if (!n.checklist) return Markdown.render(n.content == null ? "" : n.content, pal);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < n.items.size(); i++) {
            Note.CheckItem it = n.items.get(i);
            if (i > 0) sb.append('\n');
            sb.append(it.done ? "☑ " : "☐ ");
            int base = sb.length();
            sb.append(it.text == null ? "" : it.text);
            if (it.done && sb.length() > base) {
                sb.setSpan(new Markdown.Strike(), base, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new Markdown.Fg(pal.textSecondary), base, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** 整篇文档：标题（加粗放大） + 元信息（小灰字） + 渲染正文 + 附件清单。字号以调用方 TextPaint 为基准相对缩放。 */
    private static SpannableStringBuilder compose(Note n, Palette pal, float titleScale) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String title = n.title == null ? "" : n.title.trim();
        if (!title.isEmpty()) {
            int base = sb.length();
            sb.append(title);
            sb.setSpan(new Markdown.Sty(Typeface.BOLD), base, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new Markdown.Size(titleScale), base, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
        }
        int mb = sb.length();
        sb.append(metaLine(n));
        sb.setSpan(new Markdown.Fg(pal.textSecondary), mb, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new Markdown.Size(0.82f), mb, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n\n");
        sb.append(renderBody(n, pal));
        if (!n.attachments.isEmpty()) {
            sb.append("\n\n");
            int ab = sb.length();
            sb.append(Lang.t("附件："));
            sb.setSpan(new Markdown.Sty(Typeface.BOLD), ab, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (Note.Attachment a : n.attachments)
                sb.append("\n📎 ").append(a.name).append(" (").append(Attachments.formatSize(a.size)).append(")");
        }
        return sb;
    }

    private static StaticLayout layoutOf(CharSequence cs, TextPaint tp, int width, float spacing) {
        return StaticLayout.Builder.obtain(cs, 0, cs.length(), tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, spacing)
                .setIncludePad(false)
                .build();
    }

    // ---------------- PDF ----------------

    /**
     * 打印配色：PDF 固定白底，而深色模式下主题文字是浅色，直接印到白纸上几乎看不见，
     * 故深色模式时换一套深色墨水色（正文近黑、次要文字深灰、强调色压暗保留原色相）。
     */
    private static Palette printPalette(Palette pal) {
        if (!pal.dark) return pal;
        Palette p = new Palette();
        p.primary = darkenForPrint(pal.primary);
        p.onPrimary = pal.onPrimary;
        p.primaryContainer = pal.primaryContainer;
        p.background = 0xFFFFFFFF;
        p.surface = 0xFFFFFFFF;
        p.surfaceVariant = 0xFFF0EEF4;
        p.textPrimary = 0xFF1C1B1F;
        p.textSecondary = 0xFF5F5B6E;
        p.outline = pal.outline;
        p.dark = false;
        p.glass = false;
        return p;
    }

    private static int darkenForPrint(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (lum <= 0.55) return c;
        float k = 0.45f;
        return 0xFF000000 | (Math.round(r * k) << 16) | (Math.round(g * k) << 8) | Math.round(b * k);
    }

    private static void writePdf(File out, Note note, Palette pal0) throws Exception {
        final Palette pal = printPalette(pal0);
        final int pageW = 595, pageH = 842, margin = 48;
        final int contentW = pageW - margin * 2;
        final int avail = pageH - margin * 2;
        TextPaint tp = new TextPaint();
        tp.setAntiAlias(true);
        tp.setColor(pal.textPrimary);
        tp.setTextSize(12f);
        CharSequence cs = compose(note, pal, 1.7f);
        StaticLayout layout = layoutOf(cs, tp, contentW, 1.35f);
        int total = layout.getLineCount();
        // 先算每页起始行，页脚要印总页数
        List<Integer> starts = new ArrayList<>();
        int sl = 0;
        while (sl < total) {
            starts.add(sl);
            int base = layout.getLineTop(sl);
            int el = sl;
            while (el < total && layout.getLineBottom(el) - base <= avail) el++;
            if (el == sl) el = sl + 1; // 单行比整页还高时强制前进，避免死循环
            sl = el;
        }
        if (starts.isEmpty()) starts.add(0);
        TextPaint foot = new TextPaint();
        foot.setAntiAlias(true);
        foot.setColor(pal.textSecondary);
        foot.setTextSize(9f);
        PdfDocument doc = new PdfDocument();
        try {
            for (int p = 0; p < starts.size(); p++) {
                int start = starts.get(p);
                int top = layout.getLineTop(start);
                PdfDocument.Page page = doc.startPage(
                        new PdfDocument.PageInfo.Builder(pageW, pageH, p + 1).create());
                Canvas cv = page.getCanvas();
                cv.drawColor(0xFFFFFFFF);
                cv.save();
                cv.translate(margin, margin - top);
                cv.clipRect(0, top, contentW, top + avail);
                layout.draw(cv);
                cv.restore();
                String label = (p + 1) + " / " + starts.size();
                cv.drawText(label, (pageW - foot.measureText(label)) / 2f, pageH - 24, foot);
                doc.finishPage(page);
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    // ---------------- 长图 ----------------

    private static final int PNG_W = 1080, PNG_PAD = 64;
    /** 长图位图内存预算（ARGB_8888 字节数）：超长笔记靠缩字号装下整篇，绝不截断内容。 */
    private static final long PNG_BUDGET = 110L * 1024 * 1024;
    /** 单图总高上限：原字号排版在这个高度内直接出一张图，超过则弹窗让用户选多张连续图。 */
    private static final int PNG_SINGLE_LIMIT = 14000;
    /** 多张连续图时，每张的内容高度上限（按行边界切分，不会把一行字切成两半）。 */
    private static final int PNG_SLICE_H = 12000;

    private static int sliceCount(int contentH) {
        return Math.max(1, (contentH + PNG_SLICE_H - 1) / PNG_SLICE_H);
    }

    /**
     * 原字号排版后按行边界切成多张连续图片：首张带顶部留白、末张带底部留白，中间张上下衔接处不留白，
     * 拼起来就是完整一篇。文件名 名称-01.png、名称-02.png…按序号排列。
     */
    private static void writePngSlices(android.content.Context ctx, Note note, Palette pal, List<File> out)
            throws Exception {
        StaticLayout layout = buildPngLayout(note, pal, 1f);
        int lines = layout.getLineCount();
        int count = sliceCount(layout.getHeight());
        String base = baseName(note);
        int lineFrom = 0;
        for (int i = 0; i < count; i++) {
            int yFrom = layout.getLineTop(lineFrom);
            int lineTo;
            if (i == count - 1) {
                lineTo = lines - 1;
            } else {
                lineTo = lineFrom;
                while (lineTo + 1 < lines && layout.getLineBottom(lineTo + 1) - yFrom <= PNG_SLICE_H) lineTo++;
            }
            int yTo = layout.getLineBottom(lineTo);
            boolean first = i == 0, last = i == count - 1;
            int h = (yTo - yFrom) + (first ? PNG_PAD : 0) + (last ? PNG_PAD : 0);
            Bitmap bmp = Bitmap.createBitmap(PNG_W, h, Bitmap.Config.ARGB_8888);
            try {
                Canvas cv = new Canvas(bmp);
                cv.drawColor(pal.surface);
                cv.save();
                cv.translate(PNG_PAD, (first ? PNG_PAD : 0) - yFrom);
                cv.clipRect(0, yFrom, PNG_W - PNG_PAD * 2, yTo);
                layout.draw(cv);
                cv.restore();
                File f = new File(dir(ctx), String.format(Locale.US, "%s-%02d.png", base, i + 1));
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos))
                        throw new Exception("PNG compress failed");
                }
                out.add(f);
            } finally {
                bmp.recycle();
            }
            lineFrom = lineTo + 1;
        }
    }

    private static StaticLayout buildPngLayout(Note note, Palette pal, float scale) {
        TextPaint tp = new TextPaint();
        tp.setAntiAlias(true);
        tp.setColor(pal.textPrimary);
        tp.setTextSize(34f * scale);
        return layoutOf(compose(note, pal, 1.6f), tp, PNG_W - PNG_PAD * 2, 1.3f);
    }

    private static void writePng(File out, Note note, Palette pal) throws Exception {
        // 1) 定字号：整篇排版后估算位图内存，超预算就按面积比例缩字号重排，直到装得下（只缩字号、不删内容）
        float scale = 1f;
        StaticLayout layout = buildPngLayout(note, pal, scale);
        int totalH = layout.getHeight() + PNG_PAD * 2;
        long bytes = (long) PNG_W * totalH * 4;
        int guard = 0;
        while (bytes > PNG_BUDGET && scale > 0.30f && guard++ < 8) {
            scale = Math.max(0.30f, scale * (float) Math.sqrt((double) PNG_BUDGET / (double) bytes) * 0.98f);
            layout = buildPngLayout(note, pal, scale);
            totalH = layout.getHeight() + PNG_PAD * 2;
            bytes = (long) PNG_W * totalH * 4;
        }
        // 2) 渲染整篇：内存仍紧张先降色彩深度（RGB_565 减半），真 OOM 再降字号重试，始终保证全文完整
        for (int attempt = 0; ; attempt++) {
            Bitmap.Config cfg = ((long) PNG_W * totalH * 4 > PNG_BUDGET)
                    ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            Bitmap bmp = null;
            try {
                bmp = Bitmap.createBitmap(PNG_W, totalH, cfg);
                Canvas cv = new Canvas(bmp);
                cv.drawColor(pal.surface);
                cv.save();
                cv.translate(PNG_PAD, PNG_PAD);
                layout.draw(cv);
                cv.restore();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos))
                        throw new Exception("PNG compress failed");
                }
                return;
            } catch (OutOfMemoryError oom) {
                if (attempt >= 2) throw new Exception(Lang.t("内存不足，长图生成失败"));
                scale *= 0.75f;
                layout = buildPngLayout(note, pal, scale);
                totalH = layout.getHeight() + PNG_PAD * 2;
            } finally {
                if (bmp != null) bmp.recycle();
            }
        }
    }
}
