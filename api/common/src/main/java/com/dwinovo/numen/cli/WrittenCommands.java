package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文字里写着的调用:技能、系统提示、工具与动作的说明里提到的每一段 API 调用,都要经脚本的前端读得通——判据只有登记处这一处,
 * 不另记一份"有哪些函数"的清单。动作改名、改写法之后,还写着旧样子的地方由这里指出来。
 *
 * <h2>怎么认出一段调用(约定)</h2>
 * <ul>
 *   <li>它写在反引号里({@code `work.dig("ores")`}),或者是 {@code ```lua} 围起的代码块(整块是一段脚本),或别的
 *       {@code ```} 块里的一行;</li>
 *   <li>它以一个组的函数打头({@code 组.动作}),前面可以有 {@code local x =}。</li>
 * </ul>
 * 旧的写法也认出来,好指出它:一行命令({@code 组 动作 …},第一个词是一个组)与原版指令({@code /…})。别的反引号——方块 id、
 * 参数名、字符网格——不读。文字里提到调用一律写进反引号,写成能照抄的样子:一段完整的调用,或只点名一个函数({@code inv.recipe})。
 *
 * <h2>读得通</h2>
 * 一段调用交脚本的前端只读不跑({@link ScriptEngine#calls}):语法要对;调到的每个动作的对象与选项要按它的参数表读得成
 * ({@link NumenCli#invocation},和真跑同一个换法);库函数要存在;{@code mc.run} 的那一行原版指令交调用方给的那棵 MC 指令树
 * ({@link #nativeProblem})。调用都返回 nil,所以拿返回值往下算的写法在那一处停下,之前调到的照查。
 */
public final class WrittenCommands {

    private static final Pattern SPAN = Pattern.compile("`([^`\n]+)`");
    private static final String FENCE = "```";
    private static final String LUA_FENCE = "```lua";
    /** 以一个组的函数打头:{@code work.dig(...)}、{@code local r = area.has(...)}、{@code move.goto_}。 */
    private static final Pattern CALL = Pattern.compile("^(?:local\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)?"
            + "([a-z][a-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");
    /** 只点名一个函数:{@code inv.recipe}。 */
    private static final Pattern MENTION = Pattern.compile("^([a-z][a-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$");

    private WrittenCommands() {}

    /** 一段待查的文字:它在哪(给出错时指路)与正文。 */
    public record Text(String where, String body) {}

    /** 一段写错的调用:在哪、那一段、读不通的原因。 */
    public record Wrong(String where, String line, String problem) {

        /** 在哪、哪一段、报错的第一句。 */
        @Override
        public String toString() {
            return where + ": `" + line + "` — " + problem.split("\n")[0];
        }
    }

    /** 读一行原版指令(不带开头的 {@code /}):读得通返回 null,否则是原因。 */
    @FunctionalInterface
    public interface NativeReader {
        String problem(String line);
    }

    /** 按约定认出这段文字里写着的每一段调用,按出现的顺序:先是代码块里的,再是反引号里的。 */
    public static List<String> in(String text) {
        List<String> found = new ArrayList<>();
        boolean fenced = false;
        boolean lua = false;
        StringBuilder block = new StringBuilder();
        StringBuilder prose = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith(FENCE)) {
                if (fenced && lua && !block.isEmpty()) {
                    found.add(block.toString().stripTrailing());
                }
                lua = !fenced && line.equals(LUA_FENCE);
                fenced = !fenced;
                block.setLength(0);
                continue;
            }
            if (fenced && lua) {
                block.append(raw).append('\n');
            } else if (fenced) {
                if (isCode(line)) {
                    found.add(line);
                }
            } else {
                prose.append(raw).append('\n');
            }
        }
        Matcher m = SPAN.matcher(prose);
        while (m.find()) {
            String span = m.group(1).strip();
            if (isCode(span)) {
                found.add(span);
            }
        }
        return found;
    }

    /** 这段写着的是调用吗(或旧写法的命令):以一个组的函数打头,一行命令以一个组打头,或原版指令。 */
    static boolean isCode(String written) {
        if (written.length() > 1 && written.startsWith(Line.MC) && Character.isLetter(written.charAt(1))) {
            return true;
        }
        Matcher call = CALL.matcher(written);
        if (call.find() && isGroup(call.group(1))) {
            return true;
        }
        String first = written.split(" ", 2)[0];
        return written.contains(" ") && NumenCli.isTopLevel(first) && !first.equals(NumenCli.HELP);
    }

    private static boolean isGroup(String table) {
        return NumenCli.groups().stream().anyMatch(g -> ScriptEngine.IN_USE.functionName(g.name()).equals(table));
    }

    /** 这些文字里写错的调用;都读得通是空表。 */
    public static List<Wrong> check(List<Text> texts, NativeReader mc) {
        List<Wrong> wrong = new ArrayList<>();
        for (Text text : texts) {
            for (String code : in(text.body())) {
                String problem = problem(code, mc);
                if (problem != null) {
                    wrong.add(new Wrong(text.where(), code, problem));
                }
            }
        }
        return wrong;
    }

    /** 一段调用读不读得通:读得通返回 null。 */
    public static String problem(String code, NativeReader mc) {
        if (code.startsWith(Line.MC)) {
            return "a Minecraft command is run as " + McCommands.call(code.substring(1)) + " in a script";
        }
        Matcher call = CALL.matcher(code);
        if (!(call.find() && isGroup(call.group(1))) && NumenCli.isTopLevel(code.split(" ", 2)[0])) {
            return "write it as a call of the API, group.action(...): this is the old command-line way";
        }
        Matcher mention = MENTION.matcher(code);
        if (mention.matches()) {
            return NumenCli.help(code) == null ? "there is no API function " + code : null;
        }
        ScriptEngine.Reading reading;
        try {
            reading = ScriptEngine.IN_USE.calls("written", code, NumenCli.scriptCatalog());
        } catch (IllegalArgumentException unreadable) {
            return unreadable.getMessage();
        }
        if (reading.calls().isEmpty() && reading.error() != null) {
            return reading.error();
        }
        for (ScriptRun.Call c : reading.calls()) {
            if (NumenCli.libraryFunctions().containsKey(c.function())) {
                continue;
            }
            try {
                NumenCli.invocation(c);
            } catch (IllegalArgumentException wrong) {
                return wrong.getMessage();
            }
            if (c.group().equals(McCommands.GROUP) && !c.args().isEmpty()) {
                String line = Line.of(String.valueOf(c.args().get(0))).text();
                String problem = mc.problem(line);
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /**
     * 按一棵 MC 指令树读一行原版指令(不带 {@code /}):整行读完、没有报错,而且走到了可执行的一格或只是一串名字
     * ({@code setblock} 在文字里提到这条指令)。{@code source} 是按谁的权限读——要看得见文字提到的每一条。
     */
    public static <S> String nativeProblem(CommandDispatcher<S> dispatcher, String line, S source) {
        ParseResults<S> parse = dispatcher.parse(line, source);
        if (!parse.getExceptions().isEmpty()) {
            return parse.getExceptions().values().iterator().next().getMessage();
        }
        if (parse.getReader().canRead() || parse.getContext().getNodes().isEmpty()) {
            return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                    .createWithContext(parse.getReader()).getMessage();
        }
        boolean named = parse.getContext().getNodes().stream()
                .allMatch(n -> n.getNode() instanceof LiteralCommandNode);
        if (parse.getContext().getCommand() == null && !named) {
            return "stops in the middle of an argument; write the whole command or only its name";
        }
        return null;
    }

    /**
     * 登记在册的说明文字:每个组的一句话,每个动作的说明、参数说明、例子与注意,库里每个函数上面的注释,工具表里每个工具的描述与
     * 参数说明。
     */
    public static List<Text> registered() {
        List<Text> texts = new ArrayList<>();
        for (CommandGroup group : NumenCli.groups()) {
            texts.add(new Text(group.name(), group.summary()));
            for (Action action : group.actions()) {
                texts.add(new Text(action.path(), action.summary()));
                for (Param<?> p : action.params()) {
                    texts.add(new Text(action.path() + " " + p.name(), p.explained()));
                }
                for (String example : action.examples()) {
                    texts.add(new Text(action.path() + " example", "`" + example + "`"));
                }
                for (String note : action.notes()) {
                    texts.add(new Text(action.path() + " note", note));
                }
            }
        }
        NumenCli.libraryFunctions().forEach((name, fn) ->
                texts.add(new Text("library " + fn.library() + " " + name, String.join("\n", fn.defined().doc()))));
        for (NumenTool tool : ToolRegistry.all()) {
            texts.addAll(toolTexts(tool));
        }
        return texts;
    }

    /** 一个工具的描述与它 schema 里每一处参数说明。 */
    public static List<Text> toolTexts(NumenTool tool) {
        List<Text> texts = new ArrayList<>();
        texts.add(new Text("tool " + tool.name(), tool.description()));
        schemaTexts("tool " + tool.name(), tool.parameterSchema(), texts);
        return texts;
    }

    private static void schemaTexts(String where, Object node, List<Text> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("description".equals(e.getKey()) && e.getValue() instanceof String text) {
                    out.add(new Text(where, text));
                } else {
                    schemaTexts(where, e.getValue(), out);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                schemaTexts(where, item, out);
            }
        }
    }
}
