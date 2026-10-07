package com.dwinovo.numen.agent.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 一段按页给的长文字:技能正文、它的附属文件、一条札记的正文这些整份读下去的文字,一行一条。
 *
 * <h2>输出预算</h2>
 * 一页至多 {@value #MAX_LINES} 行或 {@value #MAX_BYTES} 字节(UTF-8),先到哪个算哪个——这是唯一定义它的地方,照 pi 的
 * {@code truncate.ts} 与 Claude Code 的读文件:超出时保留开头,整条整条地放,末尾写明一共几条、这一页是第几到第几条、下一页怎么取
 * ({@code [Showing 1-20 of 60. Call it again with page = 2 to continue.]})。一条自己就比整页的预算大时,单占一页,只放得下的开头,
 * 后面注明它原来多大。
 *
 * @param entries 条目,按顺序;一条可以占几行
 */
public record Listing(List<String> entries) {

    /** 一页至多多少行。 */
    public static final int MAX_LINES = 2000;
    /** 一页至多多少字节(UTF-8)。 */
    public static final int MAX_BYTES = 50 * 1024;

    public Listing {
        entries = List.copyOf(entries);
    }

    /**
     * 第 {@code page} 页(从 1 数)。
     *
     * @throws IllegalArgumentException 没有这一页:说有几页
     */
    public String page(int page) {
        List<int[]> pages = pages();
        if (page < 1 || page > pages.size()) {
            throw new IllegalArgumentException("no page " + page + "; this text has "
                    + (pages.size() == 1 ? "1 page" : "pages 1-" + pages.size()));
        }
        return render(pages, page);
    }

    /** 按预算从头切页:每页从上一页停下的那条接着放,放得下就放。一页一条都放不下时那一条单占一页。每一项是 {@code [from, to)}。 */
    private List<int[]> pages() {
        List<int[]> pages = new ArrayList<>();
        int from = 0;
        do {
            int usedBytes = 0;
            int usedLines = 0;
            int to = from;
            while (to < entries.size()) {
                String entry = entries.get(to);
                int b = 1 + bytes(entry);
                int l = lines(entry);
                if (to > from && (usedBytes + b > MAX_BYTES || usedLines + l > MAX_LINES)) {
                    break;
                }
                usedBytes += b;
                usedLines += l;
                to++;
            }
            pages.add(new int[]{from, to});
            from = to;
        } while (from < entries.size());
        return pages;
    }

    private String render(List<int[]> pages, int page) {
        int[] range = pages.get(page - 1);
        List<String> parts = new ArrayList<>();
        for (String entry : entries.subList(range[0], range[1])) {
            parts.add(within(entry, range[1] - range[0]));
        }
        if (range[1] < entries.size()) {
            parts.add("[Showing " + (range[0] + 1) + "-" + range[1] + " of " + entries.size() + ". Call it again with "
                    + "page = " + (page + 1) + " to continue.]");
        }
        return String.join("\n", parts);
    }

    /**
     * 单占一页的条目自己就超预算时,只放得下的开头(按字节,不切断一个字),后面注明它原来多大;别的条目原样。
     *
     * @param onPage 这一页上有几条
     */
    private static String within(String entry, int onPage) {
        int size = bytes(entry);
        if (onPage > 1 || (size <= MAX_BYTES && lines(entry) <= MAX_LINES)) {
            return entry;
        }
        String[] rows = entry.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < rows.length && i < MAX_LINES; i++) {
            String row = (i == 0 ? "" : "\n") + rows[i];
            if (used + bytes(row) > MAX_BYTES) {
                kept.append(cut(row, MAX_BYTES - used));
                break;
            }
            kept.append(row);
            used += bytes(row);
        }
        return kept + "\n[This entry is " + size + " bytes; only its first " + bytes(kept.toString())
                + " fit in one page.]";
    }

    /** 一段文字按 UTF-8 至多 {@code max} 字节的开头,不切断一个字。 */
    private static String cut(String text, int max) {
        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int b = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + b > max) {
                break;
            }
            used += b;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int lines(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
