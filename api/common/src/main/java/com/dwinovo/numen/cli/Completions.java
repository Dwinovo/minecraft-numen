package com.dwinovo.numen.cli;

import com.mojang.brigadier.ImmutableStringReader;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 补全引擎按她的来源给出的候选。帮助里"接下来能写什么"({@link BrigadierHelp})与写错时的"你是不是要写"
 * ({@link #didYouMean})都从这里取:Numen 自己的命令({@link NumenCli#problem})、原版与模组的指令
 * ({@link CommandRunner#problem})是同一个函数,候选全由各个参数类型与节点自己给,不维护同义词表。
 *
 * <h2>为什么不直接调 {@code getCompletionSuggestions}</h2>
 * Brigadier 的补全引擎不看 {@code requires}:原版客户端手里的指令树是服务器按它的来源滤过再发来的,用不着看。服务端手里
 * 却是整棵树,原样调会把她用不了的也列进来——没有 OP 时的 {@code give}、{@code /numen} 下玩家的管理指令。所以这里照
 * 引擎的同几步走(找到光标所在的那一层 → 那一层每个子节点按同一个上下文给候选 → 合并),只多一条:她用不了的子节点
 * 不问。结果就是她的客户端要是真有一棵树时,按 Tab 看到的那些。
 */
final class Completions {

    /** "你是不是要写"最多列几个。 */
    static final int NEAREST = 3;
    /** 原版的命名空间:不带命名空间的资源 id 就是它下面的。 */
    private static final String VANILLA = "minecraft:";

    private Completions() {}

    /**
     * 这一行在 {@code cursor} 这个位置上能写什么:光标前已经写了半截的,只留以它开头的(和按 Tab 一样)。
     * {@code parse} 是这一行(至少到 {@code cursor})按她的来源解析的结果,她是谁从它的上下文里取。
     */
    static <S> Suggestions at(ParseResults<S> parse, int cursor) {
        CommandContextBuilder<S> context = parse.getContext();
        SuggestionContext<S> here = context.findSuggestionContext(cursor);
        String line = parse.getReader().getString();
        String typed = line.substring(0, cursor);
        String lower = typed.toLowerCase(Locale.ROOT);
        int start = Math.min(here.startPos, cursor);
        List<Suggestions> found = new ArrayList<>();
        for (CommandNode<S> child : here.parent.getChildren()) {
            if (!child.canUse(context.getSource())) {
                continue;
            }
            try {
                found.add(child.listSuggestions(context.build(typed), new SuggestionsBuilder(typed, lower, start))
                        .join());
            } catch (CommandSyntaxException e) {
                // 引擎自己的口径:一个节点给候选时抛出,这一格就是没有它的候选,别的节点照给
            }
        }
        return Suggestions.merge(line, found);
    }

    /** 候选的文字,按引擎排好的顺序。 */
    static List<String> texts(Suggestions suggestions) {
        return suggestions.getList().stream().map(Suggestion::getText).toList();
    }

    /**
     * 一行写不通时接在报错与那一层用法后面的一句:出错那个位置上合法的候选里,和她写的那个词最接近的几个;
     * 一个都不够近,或者出错时这一行已经读完(缺东西,不是写错),是空串。
     */
    static <S> String didYouMean(ParseResults<S> parse) {
        ImmutableStringReader reader = parse.getReader();
        if (!reader.canRead()) {
            return "";
        }
        int at = reader.getCursor();
        String line = reader.getString();
        int end = line.indexOf(' ', at);
        String written = line.substring(at, end < 0 ? line.length() : end);
        List<String> near = nearest(written, texts(at(parse, at)));
        return near.isEmpty() ? "" : "\nDid you mean: " + String.join(", ", near) + "?";
    }

    /**
     * {@code candidates} 里离 {@code written} 最近的那几个(并列的按原来的顺序,至多 {@value #NEAREST} 个);都太远是空表。
     *
     * <p>远近是编辑距离,换位算一步({@code swtich} 与 {@code switch} 差一步)。够近的界线:五个字符以内一步,更长的两步。
     * 依据是打错字的样子——绝大多数错字只差一步(多一个、少一个、换一个、两个挨着的对调),长词偶尔两处;短词若也放宽
     * 到两步,就会指向毫不相干的词({@code tp} 与 {@code me})。只列最近的那一档:有一步之遥的,就不再陪着列两步之外的。
     * 和她写的一模一样的不算——那不是纠正。
     *
     * <p>资源 id 照原版补全的规矩认(见 {@code SharedSuggestionProvider#filterResources}):她写的不带命名空间时,
     * {@code minecraft:} 下的 id 按路径比,{@code dimond} 也找得到 {@code minecraft:diamond}。
     */
    static List<String> nearest(String written, Collection<String> candidates) {
        if (written.isEmpty()) {
            return List.of();
        }
        int best = written.length() <= 5 ? 1 : 2;
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            int d = distance(written, candidate);
            if (!written.contains(":") && candidate.startsWith(VANILLA)) {
                d = Math.min(d, distance(written, candidate.substring(VANILLA.length())));
            }
            if (d == 0 || d > best) {
                continue;
            }
            if (d < best) {
                best = d;
                out.clear();
            }
            if (out.size() < NEAREST) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** 编辑距离(多一个、少一个、换一个、相邻两个对调,各算一步)。 */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
