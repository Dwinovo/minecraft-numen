package com.dwinovo.numen.cli;

import com.mojang.brigadier.ParseResults;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一个动作:{@code numen <组> <动作> …} 的那一格。它持有这件事<b>唯一的处理函数</b>、参数表与说明,
 * 命令行、帮助、快捷工具都从这里取。
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
 *       .example("numen ftbquests submit 15CDF6A098B95FDA")
 *       .note("Takes the items from YOUR inventory; FTB decides what counts.")
 *       .seeAlso("numen ftbquests list", "numen ftbquests show");
 * }</pre>
 * <ul>
 *   <li>{@link #example}:至少一个,可以多个。模型照着例子写,比读语法可靠,所以缺了在登记那一刻抛出,
 *       和名字不合规同一种把关;每个例子也在那一刻按这一组的树解析一遍,必须整行写得通、落在这个动作上,
 *       例子与语法不会走样。</li>
 *   <li>{@link #note}:可选,多条。写会不会问主人、是不是长活、会动她的什么、不会做什么。</li>
 *   <li>{@link #seeAlso}:可选。做完这件事下一步通常用的动作,同组别组都行,写整条路径。引用在全部组到齐之后
 *       一次查全(服务器建指令树、或命令树第一次被读时),理由见 {@link NumenCli}。</li>
 * </ul>
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
    private String toolName;
    private String toolDescription;

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
     * 提升为快捷工具:模型的工具表里多一个 {@code toolName},描述是 {@code description},参数 schema 由这个
     * 动作的参数表生成。调用它就是执行这个动作——同一个处理函数,同一份回执。
     */
    public Action promote(String toolName, String description) {
        group.requireOpen();
        if (this.toolName != null) {
            throw new IllegalStateException(path() + " 已经提升为 " + this.toolName + ",一个动作只提升一次");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(path() + " 提升为 " + toolName + " 却没写工具描述");
        }
        this.toolName = toolName;
        this.toolDescription = description;
        return this;
    }

    /** 一个例子:一整行真实可用的命令,帮助里原样列出。可以调多次,按调用顺序列。 */
    public Action example(String line) {
        examples.add(requireText(line, "例子"));
        return this;
    }

    /** 一条注意:会不会问主人、是不是长活、会动她的什么、不会做什么。可以调多次,按调用顺序列。 */
    public Action note(String text) {
        notes.add(requireText(text, "注意"));
        return this;
    }

    /** 相关命令:下一步通常用的动作,写整条路径,如 {@code numen ftbquests list}。可以调多次。 */
    public Action seeAlso(String... paths) {
        for (String path : paths) {
            seeAlso.add(requireText(path, "相关命令"));
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
     * 例子的把关,登记块跑完时由组调用:至少一个;每个都在 {@code tree}(只有这一组、每个动作都长着参数的树,见
     * {@link CommandGroup#close})上整行解析通过,走到可执行的一格,而且那一格属于这个动作。这棵树的节点不设
     * {@code requires},解析用不到来源,源给 null。
     */
    void checkExamples(CommandTree<CommandSource> tree) {
        if (examples.isEmpty()) {
            throw new IllegalArgumentException(path() + " 没写例子——模型照着例子写,每个动作至少一个");
        }
        List<String> here = List.of(NumenCli.ROOT, group.name(), name);
        for (String example : examples) {
            ParseResults<CommandSource> parse = tree.parse(example, null);
            if (parse.getReader().canRead() || !parse.getExceptions().isEmpty()
                    || parse.getContext().getCommand() == null || !NumenCli.literalPath(parse).equals(here)) {
                throw new IllegalArgumentException(path() + " 的例子写不通,或者落在别的动作上: " + example);
            }
        }
    }

    /**
     * 读好的参数交给处理函数。树只把动作的可执行格长在执行它的那一侧,快捷工具也按 {@link #runsOnServer} 分路,
     * 所以到这里的源对象总是这个动作那一侧的。服务端的源先绑上这个动作,派下的活才叫得出名字
     * ({@link ServerSource#taskName})。
     */
    void execute(CommandSource source, CommandArgs args) {
        switch (source) {
            case ServerSource server -> onServer.run(server.running(this), args);
            case ClientSource client -> onClient.run(client, args);
        }
    }

    List<Param<?>> positionals() {
        return params.stream().filter(Param::required).toList();
    }

    List<Param<?>> params() {
        return params;
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

    /** 相关命令的整条路径,按登记顺序。 */
    List<String> seeAlso() {
        return seeAlso;
    }

    /** {@code numen <组> <动作>}。 */
    String path() {
        return NumenCli.ROOT + " " + label();
    }

    /** {@code <组> <动作>}:从命令派下的活就叫这个名字。 */
    String label() {
        return group.name() + " " + name;
    }

    /** 整行用法:路径 + 必填参数 + 标志。 */
    String usage() {
        StringBuilder sb = new StringBuilder(path());
        for (Param<?> p : params) {
            if (p.required()) sb.append(' ').append(p.usage());
        }
        for (Param<?> p : params) {
            if (!p.required()) sb.append(' ').append(p.usage());
        }
        return sb.toString();
    }

    /** 服务端执行?(否则在主人客户端执行。) */
    boolean runsOnServer() {
        return onServer != null;
    }

    /** 提升成的工具名;没提升是 {@code null}。 */
    String toolName() {
        return toolName;
    }

    String toolDescription() {
        return toolDescription;
    }
}
