package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一个动作组(脚本里的一张表 {@code 组.动作},一行命令里的一级命令):插件经 {@code NumenApi.registerCommands} 拿到的就是它,只能
 * 往这一组里加动作。
 *
 * <pre>{@code
 * numen.registerCommands("ftbquests", "Your quest book: chapters, quests, submitting.", quests -> {
 *     quests.server("submit", "Hand in the items a quest asks for.", Quests::submit, QUEST)
 *           .example("ftbquests.submit(\"15CDF6A098B95FDA\")");
 *     quests.client("list", "List the quests you can work on now.", Quests::list)
 *           .example("ftbquests.list()");
 * });
 * }</pre>
 *
 * <p>它不给任何通向别的组的把手,所以插件<b>够不着别人的节点</b>——"不能往别人的节点下嫁接"由形状保证,不靠约定。组名撞了、
 * 动作名撞了、参数表违反规矩({@link #checkParams})、动作没写例子或例子读不通,都在登记的那一刻抛出。
 * 登记块返回后这一组就封口,之后再往里加、再补帮助都会抛。
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
     * @param params  参数:按顺序的对象,其余是选项
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
     * 参数表的规矩,登记时查,违反就抛出——核心与插件的动作同样受约束(设计稿 {@code docs/shell.md} §二):
     * <ul>
     *   <li>名字不重复,也不是脚本语言用掉的名字(选项表的键要写得出来:{@code {in = …}} 在 Lua 里是语法错);</li>
     *   <li><b>一条命令只有一类位置参数</b>:它操作的东西,可以多个,类别({@link ArgType#noun})都一样;其余一律是标志;</li>
     *   <li><b>没有必须写的标志</b>:每个标志与可以不写的位置参数都写明不写时会怎样({@link Param#whenOmitted}),默认就是对的;</li>
     *   <li><b>开关不当位置参数</b>:{@code true}/{@code false} 不写在命令行上,开关只写 {@code --name} 或 {@code --no-name};</li>
     *   <li>可以不写的位置参数只能是最后一个;一串值当位置参数只能是最后一个——它读到行尾或下一个标志,后面的位置参数会被它吞掉;
     *       吃整行的参数只能是最后一个位置参数,而且这个动作不能再有标志——它会把后面的一切都当成自己的值。</li>
     * </ul>
     */
    private static void checkParams(String path, List<Param<?>> params) {
        Set<String> seen = new HashSet<>();
        List<Param<?>> positionals = params.stream().filter(Param::positional).toList();
        Set<String> nouns = new LinkedHashSet<>();
        for (Param<?> p : params) {
            if (!seen.add(p.name())) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 写了两次");
            }
            if (!ScriptEngine.IN_USE.isKey(p.name())) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 是脚本语言的关键字,选项表里写不出来:"
                        + "换一个名字");
            }
            boolean last = !positionals.isEmpty() && positionals.get(positionals.size() - 1) == p;
            if (p.type().isSwitch() && p.positional()) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 是开关,不能当位置参数:开关写成标志 --"
                        + p.flag() + " / --no-" + p.flag());
            }
            if (!p.required() && p.whenOmitted() == null) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 可以不写,却没写不写时会怎样"
                        + "(whenOmitted):没有必须写的标志——必须写的做成位置参数,否则给一个对的默认");
            }
            if (p.positional() && !p.required() && !last) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name() + " 是可以不写的位置参数,只能是最后一个");
            }
            if (p.type().span() == ArgType.Span.REST && (!last || positionals.size() != params.size())) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name()
                        + " 吃掉余下整行,只能是最后一个参数,且这个动作不能再有标志");
            }
            if (p.type().span() == ArgType.Span.SEVERAL && p.positional() && !last) {
                throw new IllegalArgumentException(path + " 的参数 " + p.name()
                        + " 是一串值,当位置参数只能是最后一个");
            }
            if (p.positional()) {
                nouns.add(p.type().noun());
            }
        }
        if (nouns.size() > 1) {
            throw new IllegalArgumentException(path + " 的位置参数有 " + nouns.size() + " 类对象(" + String.join("、", nouns)
                    + "):一条命令只有一类位置参数——它操作的东西,可以多个;其余写成标志");
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
     * 登记块跑完:封口,再查每个动作的例子。例子经脚本的前端读,能调的只有这一组——组这时还没进登记处,而例子只该用到这一组
     * 自己的函数。
     */
    void close() {
        open = false;
        Map<String, ScriptCatalog.Verb> verbs = new TreeMap<>();
        for (Action a : actions) {
            verbs.put(a.name(), a.verb());
        }
        ScriptCatalog catalog = new ScriptCatalog(Map.of(name, verbs), Map.of());
        for (Action a : actions) {
            a.checkExamples(catalog);
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
