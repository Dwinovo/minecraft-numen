package com.dwinovo.numen.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 帮助的样子。它是模型读的界面,措辞有单元测试的快照守着。每一层的文字都取自登记时写的说明与参数表——
 * 语法只有这一个来源,技能里不抄。
 *
 * <p>三层:根(列出各组,一组一句)、组(列出动作,一行用法一句说明)、动作(用法、说明、逐个参数)。
 * 前两层是可翻页的 {@link Listing}。带目录的动作({@link Action#catalog})的帮助后面接着那张目录,同样分页。
 * 解析出错时附上的就是出错那一层的第一页或动作帮助。
 */
final class CommandHelp {

    private CommandHelp() {}

    /** 根:各组一句。组按名字排序,与系统提示里的索引同一份条目。 */
    static Listing root(Collection<CommandGroup> groups) {
        List<String> lines = new ArrayList<>();
        for (CommandGroup g : groups) {
            lines.add("  " + groupLine(g));
        }
        return new Listing(NumenCli.ROOT + " <group> <action> [arguments]. Command groups:", lines,
                NumenCli.ROOT + " <group> --help lists a group's actions.",
                NumenCli.ROOT + " help");
    }

    /** 组:每个动作一行用法加一句说明,按登记顺序。 */
    static Listing group(CommandGroup group) {
        List<String> lines = new ArrayList<>();
        for (Action a : group.actions()) {
            lines.add("  " + a.usage() + " — " + a.summary());
        }
        String path = NumenCli.ROOT + " " + group.name();
        return new Listing(path + ": " + group.summary() + " Actions:", lines,
                path + " <action> --help explains one action.",
                path + " " + NumenCli.HELP_FLAG);
    }

    /** 动作:用法、说明、逐个参数;提升过的注明快捷工具名。 */
    static String action(Action action) {
        StringBuilder sb = new StringBuilder(action.usage()).append('\n').append(action.summary());
        for (Param<?> p : action.params()) {
            sb.append("\n  ");
            if (p.required()) {
                sb.append(p.usage()).append(" (").append(p.type().hint()).append(")");
            } else {
                sb.append("--").append(p.name()).append(" <").append(p.type().kind()).append("> (optional)");
            }
            sb.append(" — ").append(p.description());
        }
        if (action.toolName() != null) {
            sb.append("\nShortcut tool: ").append(action.toolName()).append('.');
        }
        return sb.toString();
    }

    /** 带目录的动作:动作的帮助,接着目录的标题与这具身体此刻的条目。 */
    static Listing catalog(Action action, ServerSource source) {
        return new Listing(action(action) + "\n" + action.catalogTitle(), action.catalogLines(source), "",
                action.path() + " " + NumenCli.HELP_FLAG);
    }

    /** 一组一句:根帮助与系统提示索引共用。 */
    static String groupLine(CommandGroup group) {
        return group.name() + " — " + group.summary();
    }
}
