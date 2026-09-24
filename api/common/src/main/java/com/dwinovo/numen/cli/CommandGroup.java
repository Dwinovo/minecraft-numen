package com.dwinovo.numen.cli;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个命令组({@code numen <组> …}):插件经 {@code NumenApi.registerCommands} 拿到的就是它,只能往这一组里加动作。
 *
 * <pre>{@code
 * numen.registerCommands("ftbquests", "Your quest book: chapters, quests, submitting.", quests -> {
 *     quests.server("submit", "Hand in the items a quest asks for.", Quests::submit, QUEST);
 *     quests.client("list", "List the quests you can work on now.", Quests::list)
 *           .promote("list_quests", "…");
 * });
 * }</pre>
 *
 * <p>它不给任何通向别的组或根的把手,所以插件<b>够不着别人的节点</b>——"不能往别人的节点下嫁接"由形状保证,
 * 不靠约定。组名撞了、动作名撞了、快捷工具名撞了,都在登记的那一刻抛出。登记块返回后这一组就封口,
 * 之后再往里加或再提升都会抛。
 *
 * <p>一组也可以直接就是一个动作({@link #serverDirect}):参数紧跟在组名后面,没有动作名,这一组也就不再有
 * 别的动作——具名动作会和它的参数抢同一个位置。
 */
public final class CommandGroup {

    private final String name;
    private final String summary;
    private final List<Action> actions = new ArrayList<>();
    /** 这一组直接就是的那个动作;有具名动作时为 null。 */
    private Action direct;
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

    /**
     * 这一组直接就是一个在服务端执行的动作:{@code numen <组> <参数…>},没有动作名(原版指令入口
     * {@code numen mc <command...>})。这一组从此不能再有别的动作,反过来已经有具名动作的组也不能再这样登记。
     *
     * @param summary 一句话说明,这个动作的帮助里用;组的那一句仍是登记组时写的
     */
    public Action serverDirect(String summary, Action.OnServer handler, Param<?>... params) {
        requireOpen();
        if (!actions.isEmpty()) {
            throw new IllegalArgumentException("numen " + name + " 已经有动作了,不能再直接就是一个动作");
        }
        direct = add(null, summary, List.of(params), requireHandler(handler, name), null);
        return direct;
    }

    private Action add(String action, String actionSummary, List<Param<?>> params,
                       Action.OnServer onServer, Action.OnClient onClient) {
        requireOpen();
        String path = "numen " + name + (action == null ? "" : " " + action);
        if (direct != null) {
            throw new IllegalArgumentException("numen " + name + " 直接就是一个动作,不能再加 " + path);
        }
        if (action != null && !Action.NAME.matcher(action).matches()) {
            throw new IllegalArgumentException("动作名不合规(小写字母开头,只含 [a-z0-9_]): '" + action + "'");
        }
        if (action != null && actions.stream().anyMatch(a -> action.equals(a.name()))) {
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
            throw new IllegalStateException("numen " + name + " 已经登记完了,不能再往里加");
        }
    }

    void close() {
        open = false;
    }

    /**
     * 这一组在 Brigadier 树上的样子:{@code --help}(可翻页)与各个动作,组本身不可执行;组直接就是一个动作时,
     * 那个动作的帮助与参数直接挂在组这一格下。
     */
    LiteralArgumentBuilder<CommandSource> node() {
        LiteralArgumentBuilder<CommandSource> node = LiteralArgumentBuilder.literal(name);
        if (direct != null) {
            return direct.fill(node);
        }
        node.then(NumenCli.helpNode(NumenCli.HELP_FLAG, () -> CommandHelp.group(this)));
        for (Action a : actions) {
            node.then(a.node());
        }
        return node;
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

    /** 这一组直接就是的那个动作;有具名动作的组是 null。 */
    Action direct() {
        return direct;
    }
}
