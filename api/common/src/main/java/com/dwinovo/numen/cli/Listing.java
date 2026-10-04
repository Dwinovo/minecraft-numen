package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一张可翻页的列表:抬头、条目、结尾一句(抬头与结尾都可以没有)。动作里所有会随数据变长的输出都是它:根的帮助,以及动作自己列的
 * 清单、设计的步骤、切片的行。
 *
 * <h2>输出预算</h2>
 * 一次调用的输出至多 {@value #MAX_LINES} 行或 {@value #MAX_BYTES} 字节(UTF-8),先到哪个算哪个——这是唯一定义它的地方,
 * 照 pi 的 {@code truncate.ts} 与 Claude Code 的读文件:超出时保留开头,整条整条地放,末尾写明一共几条、这一页是第几到
 * 第几条、下一页怎么取({@code [Showing 1-20 of 60. Call it again with page = 2 to continue.]})。她读不了文件,下一段
 * 就是同一次调用加上选项 {@code page}。一条条目自己就比整页的预算大时,单占一页,只放得下的开头,后面注明它原来多大。
 *
 * <p>那是整份读下去的输出(帮助、设计的步骤)的预算。有的清单读的人只用得上开头几条、其余要时再翻(由近及远的一团团方块),
 * 列它的动作给一页更小的字节预算({@code maxBytes}),切页、翻页提示还是这里这一套。
 *
 * <p>列清单的动作把 {@link #PAGE} 登记为自己的参数,处理函数里把读好的参数交给 {@link #result}:
 *
 * <pre>{@code
 * quests.client("list", "The quests you can work on now.",
 *         (src, args) -> src.reply(new Listing(head, rows, foot).result(args).toJson()),
 *         Listing.PAGE);
 * }</pre>
 *
 * @param entries 条目,按顺序;一条可以占几行(设计的一步是它那一行加一行代价)
 * @param maxBytes 一页至多多少字节(UTF-8),不大于 {@link #MAX_BYTES}
 */
public record Listing(String head, List<String> entries, String foot, int maxBytes) {

    /** 一次调用的输出至多多少行。 */
    public static final int MAX_LINES = 2000;
    /** 一次调用的输出至多多少字节(UTF-8)。 */
    public static final int MAX_BYTES = 50 * 1024;

    /** 翻页的选项:帮助认它,列清单的动作也登记它,{@code page = N} 的写法只有这一种。 */
    public static final Param<Integer> PAGE = Param.optional("page", ArgType.integer(1, 99),
            "Which page of the list.").whenOmitted("show the first page");

    private static final DynamicCommandExceptionType NO_PAGE = new DynamicCommandExceptionType(
            what -> new LiteralMessage(String.valueOf(what)));

    public Listing {
        entries = List.copyOf(entries);
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("a page holds 1 to " + MAX_BYTES + " bytes: " + maxBytes);
        }
    }

    /** 一页照整份输出的预算({@link #MAX_BYTES})。 */
    public Listing(String head, List<String> entries, String foot) {
        this(head, entries, foot, MAX_BYTES);
    }

    /** 这次调用要的那一页({@code page},没写是第一页);没有这一页是一条失败,说有几页。 */
    public TaskResult result(CommandArgs args) {
        return result(args, Map.of());
    }

    /**
     * 同 {@link #result(CommandArgs)},成功的那一页带上 {@code data}。{@code data} 不随页变:只放整份的小结(尺寸、总料单),
     * 它的大小不随条目数长。
     */
    public TaskResult result(CommandArgs args, Map<String, Object> data) {
        return result(pageIn(args), data);
    }

    /** 第 {@code page} 页(从 1 数);没有这一页是一条失败,说有几页。 */
    public TaskResult result(int page) {
        return result(page, Map.of());
    }

    private TaskResult result(int page, Map<String, Object> data) {
        List<int[]> pages = pages();
        return has(pages, page) ? TaskResult.ok(render(pages, page), data) : TaskResult.fail(noSuchPage(pages, page));
    }

    /** 帮助节点要的那一页;没有这一页时抛出,和别的解析错误一样附着用法回去。 */
    String page(CommandArgs args) throws CommandSyntaxException {
        int page = pageIn(args);
        List<int[]> pages = pages();
        if (!has(pages, page)) {
            throw NO_PAGE.create(noSuchPage(pages, page));
        }
        return render(pages, page);
    }

    /** 第一页,永远存在:出错时附的用法就是它。 */
    String first() {
        return render(pages(), 1);
    }

    private static int pageIn(CommandArgs args) {
        Integer page = args.get(PAGE);
        return page == null ? 1 : page;
    }

    /**
     * 按预算从头切页:每页从上一页停下的那条接着放,放得下就放,抬头与结尾算在里面(翻页那一句不算,和 pi 一样只算内容)。
     * 一页一条都放不下时那一条单占一页。每一项是 {@code [from, to)}。
     */
    private List<int[]> pages() {
        int baseBytes = (head.isEmpty() ? 0 : bytes(head)) + (foot.isEmpty() ? 0 : 1 + bytes(foot));
        int baseLines = (head.isEmpty() ? 0 : lines(head)) + (foot.isEmpty() ? 0 : lines(foot));
        List<int[]> pages = new ArrayList<>();
        int from = 0;
        do {
            int usedBytes = baseBytes;
            int usedLines = baseLines;
            int to = from;
            while (to < entries.size()) {
                String entry = entries.get(to);
                int b = 1 + bytes(entry);
                int l = lines(entry);
                if (to > from && (usedBytes + b > maxBytes || usedLines + l > MAX_LINES)) {
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

    private static boolean has(List<int[]> pages, int page) {
        return page >= 1 && page <= pages.size();
    }

    private String noSuchPage(List<int[]> pages, int page) {
        return "no page " + page + "; this list has " + (pages.size() == 1 ? "1 page" : "pages 1-" + pages.size());
    }

    private String render(List<int[]> pages, int page) {
        int[] range = pages.get(page - 1);
        List<String> parts = new ArrayList<>();
        if (!head.isEmpty()) {
            parts.add(head);
        }
        for (String entry : entries.subList(range[0], range[1])) {
            parts.add(within(entry, range[1] - range[0]));
        }
        if (range[1] < entries.size()) {
            parts.add("[Showing " + (range[0] + 1) + "-" + range[1] + " of " + entries.size() + ". Call it again with "
                    + "page = " + (page + 1) + " to continue.]");
        }
        if (!foot.isEmpty()) {
            parts.add(foot);
        }
        return String.join("\n", parts);
    }

    /**
     * 单占一页的条目自己就超预算时,只放得下的开头(按字节,不切断一个字),后面注明它原来多大;别的条目原样。
     *
     * @param onPage 这一页上有几条
     */
    private String within(String entry, int onPage) {
        int size = bytes(entry);
        if (onPage > 1 || (size <= maxBytes && lines(entry) <= MAX_LINES)) {
            return entry;
        }
        String[] rows = entry.split("\n", -1);
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < rows.length && i < MAX_LINES; i++) {
            String row = (i == 0 ? "" : "\n") + rows[i];
            if (used + bytes(row) > maxBytes) {
                kept.append(cut(row, maxBytes - used));
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
