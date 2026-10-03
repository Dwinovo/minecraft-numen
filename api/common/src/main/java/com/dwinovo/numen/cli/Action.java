package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.FunctionDoc;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一个动作:脚本里的一个 API 函数 {@code 名字空间.组.动作(...)},也是人写的一行命令 {@code 名字空间 组 动作 …}。它持有这件事<b>唯一的处理函数</b>、
 * 参数表与说明,两个前端、帮助、系统提示里的索引都从这里取。
 *
 * <p>执行侧由登记时给的处理函数决定:{@link CommandGroup#server} 给的是服务端函数,{@link CommandGroup#client}
 * 给的是客户端函数,二者只有一个。声明在两侧都登记(公共代码在每个进程里各跑一遍),每一侧的树由
 * {@link CommandTree} 长出来:两侧都有这个动作的名字与帮助,参数与可执行的那一格只在执行它的那一侧。专用服务器上
 * 客户端动作照样登记(帮助要它的说明),它的处理函数永远不会在那里被调用。
 *
 * <h2>帮助正文也登记在这里</h2>
 * 动作的帮助除了用法、说明、参数,还有三块,都接在登记处返回的这个动作上写:
 * <pre>{@code
 * quests.server("submit", "Hand in a quest's items from your own inventory.", QuestSubmit::submit, QUEST_ID)
 *       .example("ftbquests.quest.submit(\"15CDF6A098B95FDA\")")
 *       .note("Takes the items from YOUR inventory; FTB decides what counts.")
 *       .seeAlso("quest list", "quest show");
 * }</pre>
 * <ul>
 *   <li>{@link #example}:至少一个,可以多个,写成脚本里的样子({@code numen.work.dig(b, {count = 2})})。模型照着例子写,比读
 *       语法可靠,所以缺了在登记那一刻抛出,和名字不合规同一种把关;每个例子也在那一刻经脚本的前端读一遍(只记下调了哪些函数,
 *       不执行),必须调到这个动作、每次调用的参数都读得成,例子与语法不会走样。</li>
 *   <li>{@link #note}:可选,多条。写会不会问主人、是不是长活、会动她的什么、不会做什么。</li>
 *   <li>{@link #seeAlso}:可选。做完这件事下一步通常用的动作或库函数,同组别组都行:自己名字空间里的写 {@code 组 动作},别的名字空间
 *       里的写 {@code 名字空间 组 动作}。引用在全部组到齐之后一次查全(登记处第一次被用时),理由见 {@link NumenCli}。</li>
 * </ul>
 *
 * <h2>以谁的权威执行</h2>
 * 默认是她自己的({@link Authority#HERS})。包装模组管理指令的服务端动作可以声明借服务器的权威,作用对象写死为她
 * ({@link #authority},见 {@link Authority}):
 * <pre>{@code
 * group.server("switch", "Switch to another model.", this::switchModel, MODEL)
 *      .authority(Authority.SERVER_ON_HER)
 *      .example("ysm.model.switch(\"misc/1_alex\")");
 * }</pre>
 */
public final class Action {

    /** 动作名与组名同形:小写字母开头,小写字母、数字、下划线。 */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** 服务端动作:拿到活体与回信口,当场回结果,或把长活交给 {@code TaskDispatch}。 */
    @FunctionalInterface
    public interface OnServer {
        void run(ServerSource source, CommandArgs args);
    }

    /** 主人客户端动作:当场执行,经 {@link ClientSource#reply} 回结果。 */
    @FunctionalInterface
    public interface OnClient {
        void run(ClientSource source, CommandArgs args);
    }

    private final CommandGroup group;
    private final String name;
    private final String summary;
    private final List<Param<?>> params;
    private final OnServer onServer;
    private final OnClient onClient;
    private final List<String> examples = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> seeAlso = new ArrayList<>();
    private Authority authority = Authority.HERS;
    /** 脚本里它的函数直接返回的回执数据项;返回整份数据是 null。 */
    private String returns;
    /** 脚本里它的函数返回什么类型;登记时必须声明({@link #returns(ScriptType)})。 */
    private ScriptType returnType;
    /** 成功的调用的参数原样留在脚本回执里。 */
    private boolean echoed;

    Action(CommandGroup group, String name, String summary, List<Param<?>> params,
           OnServer onServer, OnClient onClient) {
        this.group = group;
        this.name = name;
        this.summary = summary;
        this.params = List.copyOf(params);
        this.onServer = onServer;
        this.onClient = onClient;
    }

    /**
     * 以谁的权威执行,不调就是她自己的。只有服务端动作能借服务器的权威:客户端动作不在服务端执行。
     */
    public Action authority(Authority authority) {
        group.requireOpen();
        if (authority == null) {
            throw new IllegalArgumentException(path() + " 的权威没给");
        }
        if (authority == Authority.SERVER_ON_HER && !runsOnServer()) {
            throw new IllegalArgumentException(path() + " 在主人客户端执行,借不了服务器的权威");
        }
        this.authority = authority;
        return this;
    }

    /**
     * 这个动作的函数成功时返回什么:回执的数据,类型是 {@code type}(签名里写的就是它);不返回值是 {@link ScriptType#NOTHING}。每个
     * 动作都要声明,登记块跑完时查。数据的样子由处理函数在同一处写出,位置、方块、实体用 {@link Shapes} 的写法。
     */
    public Action returns(ScriptType type) {
        group.requireOpen();
        if (type == null) {
            throw new IllegalArgumentException(path() + " 声明的返回类型是空的");
        }
        this.returnType = type;
        return this;
    }

    /**
     * 这个动作的函数成功时返回回执 {@code data} 里的 {@code key} 那一项,类型是 {@code type}:查询的结果拿来就能循环、判断
     * ({@code for _, e in ipairs(numen.scan.entities("item"))})。
     */
    public Action returns(String key, ScriptType type) {
        returns(type);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(path() + " 声明的返回项是空的");
        }
        this.returns = key;
        return this;
    }

    /**
     * 这个动作成功的调用,参数原样留在脚本回执的数据里(见 {@code ScriptCall.ECHOED}):对话流按它画出这次写下的东西,比如她的
     * 计划清单({@code numen.todo.write})。
     */
    public Action echoed() {
        group.requireOpen();
        this.echoed = true;
        return this;
    }

    /** 一个例子:一段真实可用的脚本,调到这个动作,帮助里原样列出。可以调多次,按调用顺序列。 */
    public Action example(String line) {
        examples.add(requireText(line, "例子"));
        return this;
    }

    /** 一条注意:会不会问主人、是不是长活、会动她的什么、不会做什么。可以调多次,按调用顺序列。 */
    public Action note(String text) {
        notes.add(requireText(text, "注意"));
        return this;
    }

    /**
     * 相关:下一步通常用的动作或库函数,写成一行命令的路径:自己名字空间里的 {@code quest list},别的名字空间里的
     * {@code numen use block}。可以调多次。
     */
    public Action seeAlso(String... paths) {
        for (String path : paths) {
            String written = requireText(path, "相关命令");
            seeAlso.add(written.split(" ").length == 2 ? group.namespace() + " " + written : written);
        }
        return this;
    }

    private String requireText(String text, String what) {
        group.requireOpen();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(path() + " 的" + what + "是空的");
        }
        return text;
    }

    /**
     * 例子的把关,登记块跑完时由组调用:至少一个;每个都经脚本的前端读一遍({@link ScriptEngine#calls},只记下调了哪些函数),
     * {@code catalog} 里只有这一组——例子只该用到这一组自己的函数。每个例子都要调到这个动作,每次调用的对象与选项都读得成
     * (同一个换法 {@link NumenCli#jsonOf} 与同一种参数类型)。
     */
    void checkExamples(ScriptCatalog catalog) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException(path() + " 没写例子——模型照着例子写,每个动作至少一个");
        }
        for (String example : examples) {
            ScriptEngine.Reading reading;
            try {
                reading = ScriptEngine.IN_USE.calls(function(), example, catalog);
            } catch (IllegalArgumentException wrong) {
                throw new IllegalArgumentException(path() + " 的例子读不通: " + example + " — " + wrong.getMessage());
            }
            if (reading.error() != null) {
                throw new IllegalArgumentException(path() + " 的例子读不通: " + example + " — " + reading.error());
            }
            List<ScriptRun.Call> calls = reading.calls();
            boolean here = false;
            for (ScriptRun.Call call : calls) {
                Action action = group.action(call.verb());
                try {
                    CommandArgs.fromJson(action.params(), NumenCli.jsonOf(action, call.args(), call.options()));
                } catch (IllegalArgumentException wrong) {
                    throw new IllegalArgumentException(path() + " 的例子里 " + call.function() + " 的参数读不成: "
                            + example + " — " + wrong.getMessage());
                }
                here |= action == this;
            }
            if (!here) {
                throw new IllegalArgumentException(path() + " 的例子没调到它自己: " + example);
            }
        }
    }

    /**
     * 读好的参数交给处理函数。两个前端都按 {@link #runsOnServer} 分路,所以到这里的源对象总是这个动作那一侧的。服务端的源先绑上
     * 这个动作与读好的参数,派下的活才叫得出名字({@link ServerSource#taskName}),重启后的重放才写得出那一行。
     */
    void execute(CommandSource source, CommandArgs args) {
        switch (source) {
            case ServerSource server -> onServer.run(server.running(this, args), args);
            case ClientSource client -> onClient.run(client, args);
        }
    }

    /** 位置参数,按声明顺序;可以不写的那一个(若有)在最后。 */
    List<Param<?>> positionals() {
        return params.stream().filter(Param::positional).toList();
    }

    List<Param<?>> params() {
        return params;
    }

    CommandGroup group() {
        return group;
    }

    String name() {
        return name;
    }

    String summary() {
        return summary;
    }

    List<String> examples() {
        return examples;
    }

    List<String> notes() {
        return notes;
    }

    /** 相关命令的整条路径({@code numen scan blocks}),按登记顺序。 */
    List<String> seeAlso() {
        return seeAlso;
    }

    /** {@code <名字空间> <组> <动作>}:整条路径,一行命令这样写它,登记处按它认。 */
    String path() {
        return group.namespace() + " " + group.name() + " " + name;
    }

    /** 脚本里的函数名:{@code numen.work.dig}、{@code numen.move.to};帮助、回执、派下的活都这样叫它。 */
    String function() {
        return ScriptEngine.IN_USE.function(group.fullName(), name);
    }

    /**
     * 怎么调它:函数名、按顺序的对象、选项表里能写的名字。归了组的标志整组写成一格 {@code …组名},排在组里第一个标志的位置;
     * 组里有哪些由动作自己的帮助列全({@link CommandHelp#action})。{@code numen.work.dig(place..., {count=…})}。
     */
    String usage() {
        StringBuilder sb = new StringBuilder(function()).append('(');
        List<String> parts = new ArrayList<>();
        for (Param<?> p : params) {
            if (p.positional()) {
                parts.add(p.usage());
            }
        }
        List<String> options = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (Param<?> p : params) {
            if (p.positional()) {
                continue;
            }
            if (p.group() == null) {
                options.add(p.usage());
            } else if (!groups.contains(p.group())) {
                groups.add(p.group());
                options.add("…" + p.group());
            }
        }
        if (!options.isEmpty()) {
            parts.add("{" + String.join(", ", options) + "}");
        }
        return sb.append(String.join(", ", parts)).append(')').toString();
    }

    Authority authority() {
        return authority;
    }

    /** 它的函数直接返回的回执数据项;返回整份数据是 null。 */
    String returns() {
        return returns;
    }

    /** 它的函数返回什么类型;没声明是 null(登记块跑完时查)。 */
    ScriptType returnType() {
        return returnType;
    }

    /**
     * 它的说明,给脚本引擎写成签名:按顺序的对象各一个参数(收一个或几个的标明),选项合成最后一个选项表,字段带说明。
     */
    FunctionDoc doc() {
        List<FunctionDoc.Param> out = new ArrayList<>();
        List<ScriptType.Field> options = new ArrayList<>();
        for (Param<?> p : params) {
            if (p.positional()) {
                boolean several = p.type().span() == ArgType.Span.SEVERAL;
                out.add(new FunctionDoc.Param(p.name(), several ? p.type().itemScript() : p.type().script(), several,
                        !p.required(), p.explained()));
            } else {
                options.add(ScriptType.optional(p.name(), p.type().script(), p.explained()));
            }
        }
        if (!options.isEmpty()) {
            out.add(new FunctionDoc.Param("opts", new ScriptType.Table(options), false, true, null));
        }
        List<String> notes = new ArrayList<>(this.notes);
        if (authority == Authority.SERVER_ON_HER) {
            notes.add(0, CommandHelp.SERVER_ON_HER);
        }
        return new FunctionDoc(function(), summary, out, returnType, examples, notes,
                seeAlso.stream().map(path -> ScriptEngine.IN_USE.pathName(path.replace(' ', '.'))).toList());
    }

    /** 它的脚本函数怎么交回结果、参数留不留在回执里。 */
    ScriptCatalog.Verb verb() {
        java.util.Set<String> options = new java.util.LinkedHashSet<>();
        int positions = 0;
        for (Param<?> p : params) {
            if (!p.positional()) {
                options.add(p.name());
            } else if (p.type().span() == ArgType.Span.ONE) {
                positions++;
            } else {
                positions = Integer.MAX_VALUE;
            }
        }
        return new ScriptCatalog.Verb(returns, echoed, options, positions,
                returnType == null ? null : ScriptType.sample(returnType, NumenCli::classNamed), returnType);
    }

    /** 服务端执行?(否则在主人客户端执行。) */
    boolean runsOnServer() {
        return onServer != null;
    }
}
