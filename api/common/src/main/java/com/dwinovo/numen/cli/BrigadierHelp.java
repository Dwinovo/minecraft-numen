package com.dwinovo.numen.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code help <指令>} 在原版那一行用法之后接上的几项。原版与别的模组的指令没有说明文字,我们也不替它们写;但 Brigadier
 * 带着的比原版 {@code help} 显示的多,这里统一挖出来,对任何指令一样,不逐个适配:
 *
 * <ul>
 *   <li><b>每个参数的类型</b>:参数节点上的 {@link ArgumentType},称呼是它在 MC 指令参数类型注册表里的名字,加上这一格的
 *       设定——和原版导出指令树({@code ArgumentUtils#serializeNodeToJson})写的是同一份,例如
 *       {@code minecraft:entity (amount multiple, type players)}、{@code brigadier:integer (min 1, max 6400)}。</li>
 *   <li><b>类型自带的例子</b>:{@link ArgumentType#getExamples}。</li>
 *   <li><b>接下来能写什么</b>:补全引擎按她的来源给的候选({@link Completions#at}),最多列 {@value #SHOWN} 个,再说一共几个。
 *       最后一截写了一半、还读不通(物品 id 的开头这类)的,只留以它开头的,和按 Tab 一样——长清单这样缩小,不翻页:
 *       翻页要在 {@code help} 这一行里加我们的标志,而这一行归原版的 {@code help} 解析。那一截对不上任何候选时,
 *       接上"你是不是要写"。</li>
 * </ul>
 *
 * <p>用法那一行是原版 {@code help} 自己说的(服务器按她的来源给的 {@code getSmartUsage}),这里不再写一遍。挖的参数正是
 * 那一行里出现的那些:从解析到的最后一格往下,和 {@code getSmartUsage} 走同样的路——只有一个子节点就接着往下,
 * 有几个就各列一格不再深入,可执行的一格之后只列下一格(用法里写成 {@code [<…>]} 的那格),转向别处的(redirect)不跟。
 * 想看更深的,就像原版那样多写一截:{@code help give @s}。
 */
final class BrigadierHelp {

    /** "接下来能写什么"最多列几个。 */
    static final int SHOWN = 10;

    private BrigadierHelp() {}

    /**
     * {@code help <command>} 挖出的那几项,一项一段;什么都挖不出是空串。只在原版 {@code help} 答得出这一行时调,
     * 那时它至少解析到了一格。
     */
    static <S> String mine(CommandDispatcher<S> dispatcher, String command, S her) {
        ParseResults<S> parse = dispatcher.parse(command, her);
        List<ParsedCommandNode<S>> nodes = parse.getContext().getNodes();
        CommandNode<S> last = nodes.get(nodes.size() - 1).getNode();
        StringBuilder out = new StringBuilder();

        // 原版的用法从解析到的最后一格往下写,参数也从这里往下挖
        Set<String> arguments = new LinkedHashSet<>();
        for (CommandNode<S> child : last.getChildren()) {
            walk(child, her, false, arguments);
        }
        if (!arguments.isEmpty()) {
            out.append("\nArguments:");
            arguments.forEach(line -> out.append("\n  ").append(line));
        }

        // 整行都读通了,候选在下一格(补一个空格,和按 Tab 前先敲空格一样);最后一截没读通,候选就是以它开头的那些
        boolean whole = !parse.getReader().canRead();
        List<String> next = whole
                ? Completions.texts(Completions.at(dispatcher.parse(command + " ", her), command.length() + 1))
                : Completions.texts(Completions.at(parse, command.length()));
        if (!next.isEmpty()) {
            out.append("\nCan go next");
            if (next.size() > SHOWN) {
                out.append(" (").append(SHOWN).append(" of ").append(next.size()).append(')');
            }
            out.append(": ").append(String.join(", ", next.subList(0, Math.min(SHOWN, next.size()))));
        } else if (!whole) {
            out.append(Completions.didYouMean(parse));
        }
        return out.toString();
    }

    /**
     * 和 {@code getSmartUsage} 同一条路:这一格她用不了就不算;是参数就记下;{@code deep}(用法里只写它自己的名字)
     * 或转向别处就停;否则只有一个子节点接着往下(这一格可执行,那个子节点就是可选的一格,只写名字),几个就各记一格。
     */
    private static <S> void walk(CommandNode<S> node, S her, boolean deep, Set<String> arguments) {
        if (!node.canUse(her)) {
            return;
        }
        if (node instanceof ArgumentCommandNode<S, ?> argument) {
            arguments.add(describe(argument));
        }
        if (deep || node.getRedirect() != null) {
            return;
        }
        List<CommandNode<S>> children = node.getChildren().stream().filter(c -> c.canUse(her)).toList();
        if (children.size() == 1) {
            walk(children.get(0), her, node.getCommand() != null, arguments);
        } else {
            children.forEach(c -> walk(c, her, true, arguments));
        }
    }

    /** 一格参数:用法里的名字、类型的称呼、类型自带的例子。 */
    static String describe(ArgumentCommandNode<?, ?> argument) {
        String line = argument.getUsageText() + " " + typeName(argument.getType());
        Collection<String> examples = argument.getType().getExamples();
        return examples.isEmpty() ? line : line + " — e.g. " + String.join(", ", examples);
    }

    /**
     * 参数类型的称呼:注册表里的名字,这一格有设定时括号里接上,每项 {@code 名 值}。设定由类型自己的
     * {@link ArgumentTypeInfo} 写出,和原版把指令树写成 JSON 时是同一份。
     */
    static String typeName(ArgumentType<?> type) {
        ArgumentTypeInfo.Template<?> template = ArgumentTypeInfos.unpack(type);
        String name = String.valueOf(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(template.type()));
        JsonObject settings = new JsonObject();
        writeSettings(template, settings);
        if (settings.size() == 0) {
            return name;
        }
        return name + " (" + settings.entrySet().stream()
                .map(e -> e.getKey() + " " + plain(e.getValue()))
                .collect(Collectors.joining(", ")) + ")";
    }

    private static <A extends ArgumentType<?>> void writeSettings(ArgumentTypeInfo.Template<A> template,
                                                                 JsonObject out) {
        writeSettings(template.type(), template, out);
    }

    @SuppressWarnings("unchecked")
    private static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> void writeSettings(
            ArgumentTypeInfo<A, T> info, ArgumentTypeInfo.Template<A> template, JsonObject out) {
        info.serializeToJson((T) template, out);
    }

    /** JSON 里的一个设定值写成字面:字符串不带引号。 */
    private static String plain(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
}
