package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import java.util.List;

/**
 * 一张可翻页的列表:抬头、条目、结尾一句(可以没有),以及翻页时要写的那条命令。每页 {@value #PAGE_SIZE} 行,
 * 超出时说还剩几条、下一页怎么翻({@code --page})。
 *
 * <p>命令里所有分页的输出都是它:根与组的帮助、带目录的动作帮助,以及动作自己列的清单。列清单的动作把
 * {@link #PAGE} 登记为自己的参数,处理函数里把读好的参数交给 {@link #result}:
 *
 * <pre>{@code
 * quests.client("list", "The quests you can work on now.",
 *         (src, args) -> src.reply(new Listing(head, rows, foot, "numen ftbquests list").result(args).toJson()),
 *         Listing.PAGE);
 * }</pre>
 *
 * @param again 翻页时写的那条命令,{@code --page N} 接在它后面
 */
public record Listing(String head, List<String> lines, String foot, String again) {

    static final int PAGE_SIZE = 20;

    /** 翻页的标志:帮助认它,列清单的动作也登记它,{@code --page N} 的写法只有这一种。 */
    public static final Param<Integer> PAGE = Param.optional("page", ArgType.integer(1, 99),
            "Which page of the list.");

    private static final DynamicCommandExceptionType NO_PAGE = new DynamicCommandExceptionType(
            what -> new LiteralMessage(String.valueOf(what)));

    public Listing {
        lines = List.copyOf(lines);
    }

    /** 这次调用要的那一页({@code --page},没写是第一页);没有这一页是一条失败,说有几页。 */
    public TaskResult result(CommandArgs args) {
        int page = pageIn(args);
        return has(page) ? TaskResult.ok(render(page)) : TaskResult.fail(noSuchPage(page));
    }

    /** 帮助节点要的那一页;没有这一页时抛出,和别的解析错误一样附着用法回去。 */
    String page(CommandArgs args) throws CommandSyntaxException {
        int page = pageIn(args);
        if (!has(page)) {
            throw NO_PAGE.create(noSuchPage(page));
        }
        return render(page);
    }

    /** 第一页,永远存在:出错时附的用法就是它。 */
    String first() {
        return render(1);
    }

    private static int pageIn(CommandArgs args) {
        Integer page = args.get(PAGE);
        return page == null ? 1 : page;
    }

    private int pages() {
        return Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private boolean has(int page) {
        return page >= 1 && page <= pages();
    }

    private String noSuchPage(int page) {
        return "no page " + page + "; " + again + " has " + (pages() == 1 ? "1 page" : "pages 1-" + pages());
    }

    private String render(int page) {
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(lines.size(), from + PAGE_SIZE);
        StringBuilder sb = new StringBuilder(head);
        for (String line : lines.subList(from, to)) {
            sb.append('\n').append(line);
        }
        if (to < lines.size()) {
            sb.append("\n(page ").append(page).append(" of ").append(pages()).append(", ")
                    .append(lines.size() - to).append(" more: ").append(again)
                    .append(" --page ").append(page + 1).append(')');
        }
        return foot.isEmpty() ? sb.toString() : sb.append('\n').append(foot).toString();
    }
}
