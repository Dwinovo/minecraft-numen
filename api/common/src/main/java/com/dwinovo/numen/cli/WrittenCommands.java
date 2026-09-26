package com.dwinovo.numen.cli;

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
 * 文字里写着的命令:技能、系统提示、工具与动作的说明里提到的每一行命令,都要按命令树读得通——判据只有命令树这一处,
 * 不另记一份"有哪些命令"的清单。命令改名、改写法之后,还写着旧样子的地方由这里指出来。
 *
 * <h2>怎么认出一行命令(约定)</h2>
 * <ul>
 *   <li>它写在反引号里({@code `use gui`}),或者是 {@code ```} 围起的代码块里的一行;</li>
 *   <li>它以 {@code /} 打头(第 0 层,原版与模组的指令),或它的第一个词是第 1 层的一级命令(一个命令组的名字,
 *       或 {@code help})。</li>
 * </ul>
 * 别的反引号——方块 id、工具名、参数名、字符网格——不是命令,不读。所以文字里提到命令一律写进反引号,写成能照抄的
 * 样子:一条完整的命令,或只点名一组、一个动作({@code inv recipe})。占位符({@code <x>})与省略号不是命令的写法。
 *
 * <h2>读得通</h2>
 * 第 1 层交 {@link NumenCli#read}:和执行时同一个解析器,整行是一条能执行的命令,或整行只是一串名字。第 0 层交调用方给的
 * 那棵 MC 指令树({@link #nativeProblem}),同样两种读得通。
 */
public final class WrittenCommands {

    private static final Pattern SPAN = Pattern.compile("`([^`\n]+)`");
    private static final String FENCE = "```";

    private WrittenCommands() {}

    /** 一段待查的文字:它在哪(给出错时指路)与正文。 */
    public record Text(String where, String body) {}

    /** 一行写错的命令:在哪、那一行、读不通的原因。 */
    public record Wrong(String where, String line, String problem) {

        /** 在哪、哪一行、报错的第一句;报错里有"你是不是要写"就接上它(帮助正文不抄,那一层的帮助人人都查得到)。 */
        @Override
        public String toString() {
            String[] said = problem.split("\n");
            String hint = said[said.length - 1].startsWith(Completions.DID_YOU_MEAN) ? " " + said[said.length - 1] : "";
            return where + ": `" + line + "` — " + said[0] + hint;
        }
    }

    /** 读一行第 0 层指令(不带开头的 {@code /}):读得通返回 null,否则是原因。 */
    @FunctionalInterface
    public interface NativeReader {
        String problem(String line);
    }

    /** 按约定认出这段文字里写着的每一行命令,按出现的顺序。 */
    public static List<String> in(String text) {
        List<String> found = new ArrayList<>();
        boolean fenced = false;
        StringBuilder prose = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith(FENCE)) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                if (isCommand(line)) {
                    found.add(line);
                }
            } else {
                prose.append(raw).append('\n');
            }
        }
        Matcher m = SPAN.matcher(prose);
        while (m.find()) {
            String span = m.group(1).strip();
            if (isCommand(span)) {
                found.add(span);
            }
        }
        return found;
    }

    /** 这段写着的是命令吗:{@code /} 打头接着字母,或第一个词是第 1 层的一级命令。 */
    static boolean isCommand(String written) {
        if (written.length() > 1 && written.startsWith(Line.MC) && Character.isLetter(written.charAt(1))) {
            return true;
        }
        return !written.isEmpty() && NumenCli.isTopLevel(written.split(" ", 2)[0]);
    }

    /** 这些文字里写错的命令;都读得通是空表。 */
    public static List<Wrong> check(List<Text> texts, NativeReader mc) {
        List<Wrong> wrong = new ArrayList<>();
        for (Text text : texts) {
            for (String line : in(text.body())) {
                String problem = problem(line, mc);
                if (problem != null) {
                    wrong.add(new Wrong(text.where(), line, problem));
                }
            }
        }
        return wrong;
    }

    /** 一行命令读不读得通:读得通返回 null。第 0 层去掉开头的 {@code /} 交 {@code mc}。 */
    public static String problem(String line, NativeReader mc) {
        Line routed = Line.of(line);
        if (routed.mc()) {
            return mc.problem(routed.text());
        }
        try {
            NumenCli.read(routed.text());
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * 按一棵 MC 指令树读一行第 0 层指令(不带 {@code /}):整行读完、没有报错,而且走到了可执行的一格或只是一串名字
     * ({@code /setblock} 在文字里提到这条指令)。{@code source} 是按谁的权限读——要看得见文字提到的每一条。
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
     * 登记在册的说明文字:每个命令组的一句话,每个动作的说明、参数说明、例子与注意,工具表里每个工具的描述与参数说明
     * (快捷工具、{@code command} 工具、独立工具都在这张表里)。
     */
    public static List<Text> registered() {
        List<Text> texts = new ArrayList<>();
        for (CommandGroup group : NumenCli.groups()) {
            texts.add(new Text(group.name(), group.summary()));
            for (Action action : group.actions()) {
                texts.add(new Text(action.path(), action.summary()));
                for (Param<?> p : action.params()) {
                    texts.add(new Text(action.path() + " --" + p.name(), p.explained()));
                }
                for (String example : action.examples()) {
                    texts.add(new Text(action.path() + " example", "`" + example + "`"));
                }
                for (String note : action.notes()) {
                    texts.add(new Text(action.path() + " note", note));
                }
            }
        }
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
