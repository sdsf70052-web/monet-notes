package com.muse.monetnotes;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 支持（v1.43）：
 * - style()：编辑时给原文叠加 span 样式（标题变大加粗、粗斜体、代码底色、引用变灰、待办划线），原文一字不改；
 * - render()：预览模式把原文渲染成去掉语法符号的干净排版；
 * - strip()：主页卡片预览用的纯文本（剥掉语法符号）。
 * 自有 span 都是下方嵌套子类，与笔记内搜索用的纯 BackgroundColorSpan 区分开，互不清除。
 */
final class Markdown {

    static final class Bg extends BackgroundColorSpan { Bg(int c) { super(c); } }
    static final class Fg extends ForegroundColorSpan { Fg(int c) { super(c); } }
    static final class Sty extends StyleSpan { Sty(int s) { super(s); } }
    static final class Size extends RelativeSizeSpan { Size(float f) { super(f); } }
    static final class Strike extends StrikethroughSpan { }
    static final class Mono extends TypefaceSpan { Mono() { super("monospace"); } }
    static final class U extends UnderlineSpan { }

    private static final Class<?>[] OWN = {Bg.class, Fg.class, Sty.class, Size.class, Strike.class, Mono.class, U.class};

    private static final Pattern P_HEAD_PRE = Pattern.compile("^(#{1,6})\\s+");
    private static final Pattern P_QUOTE_PRE = Pattern.compile("^>\\s?");
    private static final Pattern P_CHECK = Pattern.compile("^(\\s*[-*+]\\s\\[( |x|X)\\]\\s+)(.*)$");
    private static final Pattern P_BULLET_PRE = Pattern.compile("^(\\s*[-*+]\\s+)");
    private static final Pattern P_NUMBER_PRE = Pattern.compile("^(\\s*\\d{1,9}[.)]\\s+)");
    private static final Pattern P_DIV = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern P_TABLE = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern P_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern P_BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__");
    private static final Pattern P_ITALIC = Pattern.compile("\\*([^*\\n]+)\\*|_([^_\\n]+)_");
    private static final Pattern P_STRIKE = Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern P_LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");
    private static final float[] HEAD_SIZE = {1.55f, 1.4f, 1.28f, 1.16f, 1.08f, 1.03f};

    // ---------------- 编辑时样式 ----------------

    static void style(Editable e, Palette pal) {
        if (e == null) return;
        int len = e.length();
        for (Class<?> c : OWN) {
            Object[] ss = e.getSpans(0, len, (Class) c);
            for (Object s : ss) e.removeSpan(s);
        }
        if (len == 0 || len > 150000) return;
        int codeBg = Palette.withAlpha(pal.textSecondary, 0x2E);
        String s = e.toString();
        boolean inCode = false;
        int pos = 0;
        while (pos <= len) {
            int nl = s.indexOf('\n', pos);
            int le = nl < 0 ? len : nl;
            String line = s.substring(pos, le);
            String trim = line.trim();
            if (trim.startsWith("```")) {
                set(e, new Mono(), pos, le); set(e, new Fg(pal.textSecondary), pos, le);
                inCode = !inCode;
            } else if (inCode) {
                set(e, new Mono(), pos, le); set(e, new Bg(codeBg), pos, le);
            } else {
                styleLine(e, s, pos, le, line, pal, codeBg);
            }
            if (nl < 0) break;
            pos = nl + 1;
        }
    }

    private static void styleLine(Editable e, String full, int ls, int le, String line, Palette pal, int codeBg) {
        Matcher m;
        if ((m = P_HEAD_PRE.matcher(line)).find()) {
            int lvl = m.group(1).length();
            set(e, new Sty(Typeface.BOLD), ls, le);
            set(e, new Size(HEAD_SIZE[lvl - 1]), ls, le);
            set(e, new Fg(pal.primary), ls, ls + m.end());
        } else if ((m = P_CHECK.matcher(line)).find()) {
            set(e, new Fg(pal.primary), ls, ls + m.end(1));
            if (!" ".equals(m.group(2))) {
                set(e, new Strike(), ls + m.end(1), le);
                set(e, new Fg(pal.textSecondary), ls + m.end(1), le);
            }
        } else if ((m = P_QUOTE_PRE.matcher(line)).find()) {
            set(e, new Fg(pal.textSecondary), ls, le);
            set(e, new Sty(Typeface.ITALIC), ls, le);
            set(e, new Fg(pal.primary), ls, ls + Math.max(1, m.end()));
        } else if ((m = P_BULLET_PRE.matcher(line)).find()) {
            set(e, new Fg(pal.primary), ls, ls + m.end(1));
        } else if ((m = P_NUMBER_PRE.matcher(line)).find()) {
            set(e, new Fg(pal.primary), ls, ls + m.end(1));
        } else if (P_DIV.matcher(line).matches()) {
            set(e, new Fg(pal.textSecondary), ls, le);
            return;
        } else if (P_TABLE.matcher(line).matches()) {
            set(e, new Mono(), ls, le);
        }
        styleInline(e, ls, le, line, pal, codeBg);
    }

    private static void styleInline(Editable e, int ls, int le, String line, Palette pal, int codeBg) {
        // 先标行内代码，其余语法跳过代码区间，避免 `**` 之类在代码里被误染
        java.util.List<int[]> codeRanges = new java.util.ArrayList<>();
        Matcher m = P_CODE.matcher(line);
        while (m.find()) {
            set(e, new Mono(), ls + m.start(), ls + m.end());
            set(e, new Bg(codeBg), ls + m.start(), ls + m.end());
            codeRanges.add(new int[]{m.start(), m.end()});
        }
        applyInline(e, ls, line, P_BOLD, pal, codeRanges, (a, b) -> set(e, new Sty(Typeface.BOLD), a, b));
        applyInline(e, ls, line, P_ITALIC, pal, codeRanges, (a, b) -> set(e, new Sty(Typeface.ITALIC), a, b));
        applyInline(e, ls, line, P_STRIKE, pal, codeRanges, (a, b) -> set(e, new Strike(), a, b));
        applyInline(e, ls, line, P_LINK, pal, codeRanges, (a, b) -> { set(e, new Fg(pal.primary), a, b); set(e, new U(), a, b); });
    }

    private interface SpanAct { void apply(int a, int b); }

    private static void applyInline(Editable e, int ls, String line, Pattern p, Palette pal,
                                    java.util.List<int[]> skip, SpanAct act) {
        Matcher m = p.matcher(line);
        while (m.find()) {
            boolean inSkip = false;
            for (int[] r : skip) if (m.start() < r[1] && m.end() > r[0]) { inSkip = true; break; }
            if (!inSkip) act.apply(ls + m.start(), ls + m.end());
        }
    }

    private static void set(Editable e, Object span, int a, int b) {
        if (b > a) e.setSpan(span, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------- 预览渲染 ----------------

    static CharSequence render(String src, Palette pal) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int codeBg = Palette.withAlpha(pal.textSecondary, 0x2E);
        boolean inCode = false;
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) out.append('\n');
            String trim = line.trim();
            if (trim.startsWith("```")) { inCode = !inCode; continue; }
            int base = out.length();
            if (inCode) {
                out.append(line);
                spanAll(out, new Mono(), base, out.length());
                spanAll(out, new Bg(codeBg), base, out.length());
                continue;
            }
            Matcher m;
            if ((m = P_HEAD_PRE.matcher(line)).find()) {
                int lvl = m.group(1).length();
                appendInline(out, line.substring(m.end()), pal, codeBg);
                spanAll(out, new Sty(Typeface.BOLD), base, out.length());
                spanAll(out, new Size(HEAD_SIZE[lvl - 1]), base, out.length());
            } else if ((m = P_CHECK.matcher(line)).find()) {
                boolean done = !" ".equals(m.group(2));
                out.append(done ? "☑ " : "☐ ");
                int textBase = out.length();
                appendInline(out, m.group(3), pal, codeBg);
                if (done) {
                    spanAll(out, new Strike(), textBase, out.length());
                    spanAll(out, new Fg(pal.textSecondary), textBase, out.length());
                }
            } else if ((m = P_QUOTE_PRE.matcher(line)).find()) {
                appendInline(out, line.substring(m.end()), pal, codeBg);
                spanAll(out, new Fg(pal.textSecondary), base, out.length());
                spanAll(out, new Sty(Typeface.ITALIC), base, out.length());
            } else if ((m = P_BULLET_PRE.matcher(line)).find()) {
                out.append(m.group(1).replaceAll("[-*+]", "•"));
                appendInline(out, line.substring(m.end(1)), pal, codeBg);
            } else if ((m = P_NUMBER_PRE.matcher(line)).find()) {
                out.append(m.group(1));
                appendInline(out, line.substring(m.end(1)), pal, codeBg);
            } else if (P_DIV.matcher(line).matches()) {
                out.append("─ ─ ─");
                spanAll(out, new Fg(pal.textSecondary), base, out.length());
            } else if (P_TABLE.matcher(line).matches()) {
                out.append(line);
                spanAll(out, new Mono(), base, out.length());
            } else {
                appendInline(out, line, pal, codeBg);
            }
        }
        return out;
    }

    /** 把一行的行内语法渲染进 out：符号剥掉、样式保留。按最早出现的语法逐段处理。 */
    private static void appendInline(SpannableStringBuilder out, String line, Palette pal, int codeBg) {
        Pattern[] pats = {P_CODE, P_BOLD, P_STRIKE, P_LINK, P_ITALIC};
        int pos = 0;
        while (pos < line.length()) {
            Matcher best = null;
            int kind = -1;
            for (int k = 0; k < pats.length; k++) {
                Matcher mm = pats[k].matcher(line);
                if (mm.find(pos) && (best == null || mm.start() < best.start())) { best = mm; kind = k; }
            }
            if (best == null) { out.append(line.substring(pos)); break; }
            out.append(line.substring(pos, best.start()));
            int base = out.length();
            String inner;
            if (kind == 3) inner = best.group(1);                       // 链接只留文字
            else inner = best.group(1) != null ? best.group(1) : best.group(2);
            out.append(inner == null ? "" : inner);
            switch (kind) {
                case 0: spanAll(out, new Mono(), base, out.length()); spanAll(out, new Bg(codeBg), base, out.length()); break;
                case 1: spanAll(out, new Sty(Typeface.BOLD), base, out.length()); break;
                case 2: spanAll(out, new Strike(), base, out.length()); break;
                case 3: spanAll(out, new Fg(pal.primary), base, out.length()); spanAll(out, new U(), base, out.length()); break;
                case 4: spanAll(out, new Sty(Typeface.ITALIC), base, out.length()); break;
            }
            pos = best.end();
        }
    }

    private static void spanAll(SpannableStringBuilder sb, Object span, int a, int b) {
        if (b > a) sb.setSpan(span, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------- 纯文本剥离（卡片预览） ----------------

    static String strip(String src) {
        if (src == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;
        for (String line : src.split("\n", -1)) {
            String trim = line.trim();
            if (trim.startsWith("```")) { inCode = !inCode; continue; }
            if (sb.length() > 0) sb.append('\n');
            if (inCode) { sb.append(line); continue; }
            Matcher m;
            if ((m = P_HEAD_PRE.matcher(line)).find()) line = line.substring(m.end());
            else if ((m = P_CHECK.matcher(line)).find())
                line = (!" ".equals(m.group(2)) ? "☑ " : "☐ ") + m.group(3);
            else if ((m = P_QUOTE_PRE.matcher(line)).find()) line = line.substring(m.end());
            else if ((m = P_BULLET_PRE.matcher(line)).find())
                line = m.group(1).replaceAll("[-*+]", "•") + line.substring(m.end(1));
            if (P_DIV.matcher(line).matches()) line = "─ ─ ─";
            sb.append(stripInline(line));
        }
        return sb.toString();
    }

    private static String stripInline(String line) {
        line = P_LINK.matcher(line).replaceAll("$1");
        line = P_BOLD.matcher(line).replaceAll("$1$2");
        line = P_STRIKE.matcher(line).replaceAll("$1");
        line = P_CODE.matcher(line).replaceAll("$1");
        line = P_ITALIC.matcher(line).replaceAll("$1$2");
        return line;
    }
}
