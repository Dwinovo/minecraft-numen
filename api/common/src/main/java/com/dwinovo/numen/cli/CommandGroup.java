package com.dwinovo.numen.cli;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个命令组({@code <组> …},组名就是一级命令):插件经 {@code NumenApi.registerCommands} 拿到的就是它,只能往这一组里加动作。
 *
 * <pre>{@code
 * numen.registerCommands("ftbquests", "Your quest book: chapters, quests, submitting.", quests -> {
 *     quests.server("submit", "Hand in the items a quest asks for.", Quests::submit, QUEST)
 *           .example("ftbquests submit 15CDF6A098B95FDA");
 *     quests.client("list", "List the quests you can work on now.", Quests::list)
 *           .example("ftbquests list")
 *           .promote("list_quests", "…");
 * });
 * }</pre>
 *
 * <p>它不给任何通向别的组或根的把手,所以插件<b>够不着别人的节点</b>——"不能往别人的节点下嫁接"由形状保证,
 * 不靠约定。组名撞了、动作名撞了、快捷工具名撞了、动作没写例子或例子写不通,都在登记的那一刻抛出。
 * 登记块返回后这一组就封口,之后再往里加、再提升、再补帮助都会抛。
 */
public final class CommandGroup {

    private final String name;
    private final String summary;
    private final List<Action> actions = new ArrayList<>();
    private boolean open = true;

    CommandGroup(String name, String summary) {
        this.name = name;
        this.summary = summary;
    }

    /**
     * 加一个在服务端执行的动作(动身体、读世界的都是这种)。
     *
     * @param name    动作名,小写英文
     * @param summary 一句话说明,列表与帮助里用
     * @param params  参数:必填的依次是位置参数,可选的是 {@code --name value} 标志
     */
    public Action server(String name, String summary, Action.OnServer handler, Param<?>... params) {
        return add(name, summary, List.of(params), requireHandler(handler, name), null);
    }

    /** 加一个在主人客户端执行的动作(只有客户端才有的数据或逻辑)。参数同 {@link #server}。 */
    public Action client(String name, String summary, Action.OnClient handler, Param<?>... params) {
        return add(name, summary, List.of(params), null, requireHandler(handler, name));
    }

    private Action add(String action, String actionSummary, List<Param<?>> params,
                       Action.OnServer onServer, Action.OnClient onClient) {
        requireOpen();
        String path = name + " " + action;
        if (action == null || !Action.NAME.matcher(action).matches()) {
            throw new IllegalArgumentException("动作名不合规(小写字母开头,只含 [a-z0-9_]): '" + action + "'");
        }
        if (actions.stream().anyMatch(a -> action.equals(a.name()))) {
            throw new IllegalArgumentException(path + " 登记了两次");
        }
        if (actionSummary == null || actionSummary.isBlank()) {
            throw new IllegalArgumentException(path + " 没写一句话说明");
        }
        checkParams(path, params);
        Action a = new Action(this, action, actionSummary, params, onServer, onClient);
        actions.add(a);
        return a;
    }

    /**
     * 参数表的两条硬规矩:名字不重复;吃整行的参数只能是最后一个必填参数,而且这个动作不能再有标志——
     * 它会把后面的一切都当成自己的值。
     */
    private static void checkParams(String path, List<Param<?>> params) {
        Set<String> seen = new HashSet<>();
        List<Param<?>> required = params.stream().filter(Param::required).toList();
        for (Param<?> p : params) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 写了两次");
            }
            if (p.type().restOfLine()) {
                boolean last = required.get(required.size() - 1) == p;
                if (!last || required.size() != params.size()) {
                    throw new IllegalArgumentException(path + " 的参数 " + p.name()
                            + " 吃掉余下整行,只能是最后一个参数,且这个动作不能再有标志");
                }
            }
        }
    }

    private static <H> H requireHandler(H handler, String action) {
        if (handler == null) {
            throw new IllegalArgumentException("动作 " + action + " 没给处理函数");
        }
        return handler;
    }

    void requireOpen() {
        if (!open) {
            throw new IllegalStateException("命令组 " + name + " 已经登记完了,不能再往里加");
        }
    }

    /**
     * 登记块跑完:封口,再查每个动作的例子。例子在一棵只有这一组的树上解析——组这时还没挂上共享的树,
     * 而例子只该用到这一组自己的语法。这棵树由两侧的树同一个生成器长出来,只是每个动作都长着参数:
     * 服务端动作与客户端动作的例子按同一种形状解析。
     */
    void close() {
        open = false;
        CommandTree<CommandSource> tree = new CommandTree<>(action -> true);
        tree.add(this);
        for (Action a : actions) {
            a.checkExamples(tree);
        }
    }

    String name() {
        return name;
    }

    String summary() {
        return summary;
    }

    List<Action> actions() {
        return actions;
    }

    Action action(String actionName) {
        for (Action a : actions) {
            if (actionName.equals(a.name())) return a;
        }
        return null;
    }
}
