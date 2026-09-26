package com.dwinovo.numen.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 帮助的样子。它是模型读的界面,措辞有单元测试的快照守着。每一层的文字都取自登记时写的说明与参数表——
 * 语法只有这一个来源,技能里不抄。
 *
 * <p>三层:根(列出各组,一组一句)、组(列出动作,一行用法一句说明)、动作(用法、说明、逐个参数、例子、注意、
 * 相关命令)。帮助每次都进上下文,所以只有最后一层是全量:例子与注意只写在动作的帮助里,组的帮助仍一行一个动作。
 * 前两层是可翻页的 {@link Listing}。解析出错时附上的就是出错那一层的第一页或动作帮助。
 */
final class CommandHelp {

    /** 借了服务器权威的动作,帮助里写明的那一句。 */
    static final String SERVER_ON_HER = "Runs with the server's authority, and only on you.";

    private CommandHelp() {}

    /** 根:各组一句。组按名字排序,与系统提示里的索引同一份条目。 */
    static Listing root(Collection<CommandGroup> groups) {
        List<String> lines = new ArrayList<>();
        for (CommandGroup g : groups) {
            lines.add("  " + groupLine(g));
        }
        return new Listing("<group> <action> [arguments]. Command groups:", lines,
                "<group> --help lists a group's actions. A line starting with / is a native command instead ("
                        + "/help lists those).",
                NumenCli.HELP);
    }

    /** 组:每个动作一行用法加一句说明,按登记顺序。 */
    static Listing group(CommandGroup group) {
        List<String> lines = new ArrayList<>();
        for (Action a : group.actions()) {
            lines.add("  " + a.usage() + " — " + a.summary());
        }
        String path = group.name();
        return new Listing(path + ": " + group.summary() + " Actions:", lines,
                path + " <action> --help explains one action.",
                path + " " + NumenCli.HELP_FLAG);
    }

    /**
     * 动作,给全:用法;缩进一格依次是一句说明、借了服务器的权威时写明这一句、逐个参数(类型的完整称呼,说明接取值提示)、
     * 例子、注意、相关命令;提升过的最后注明快捷工具名。没有注意、没有相关命令时那一块不出现;她自己的权威是默认,不写。
     * 归了组的标志排在不归组的参数之后,每组一小节,标题就是用法行里那一格的组名。
     */
    static String action(Action action) {
        StringBuilder sb = new StringBuilder(action.usage()).append("\n  ").append(action.summary());
        if (action.authority() == Authority.SERVER_ON_HER) {
            sb.append("\n  ").append(SERVER_ON_HER);
        }
        List<String> groups = new ArrayList<>();
        for (Param<?> p : action.params()) {
            if (p.group() == null) {
                sb.append("\n  ").append(paramLine(p));
            } else if (!groups.contains(p.group())) {
                groups.add(p.group());
            }
        }
        for (String group : groups) {
            List<String> lines = new ArrayList<>();
            for (Param<?> p : action.params()) {
                if (group.equals(p.group())) {
                    lines.add(paramLine(p));
                }
            }
            block(sb, Character.toUpperCase(group.charAt(0)) + group.substring(1) + ":", lines);
        }
        block(sb, "Examples:", action.examples());
        block(sb, "Notes:", action.notes());
        if (!action.seeAlso().isEmpty()) {
            sb.append("\n  See also: ").append(String.join(", ", action.seeAlso()));
        }
        if (action.toolName() != null) {
            sb.append("\n  Shortcut tool: ").append(action.toolName()).append('.');
        }
        return sb.toString();
    }

    /** 一个参数的那一行:写法、类型的完整称呼,接说明与取值提示。 */
    private static String paramLine(Param<?> p) {
        String head = p.required()
                ? p.usage() + " (" + p.type().hint() + ")"
                : "--" + p.name() + " <" + p.type().kind() + "> (" + p.type().hint() + "; optional)";
        return head + " — " + p.explained();
    }

    /** 带标题的一块,一行一条,再缩进一格;没有条目时整块不出现。 */
    private static void block(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n  ").append(title);
        for (String line : lines) {
            sb.append("\n    ").append(line);
        }
    }

    /** 一组一句:根帮助与系统提示索引共用。 */
    static String groupLine(CommandGroup group) {
        return group.name() + " — " + group.summary();
    }
}
