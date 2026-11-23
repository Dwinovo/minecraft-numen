package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.RuleSet;
import com.dwinovo.numen.permission.Vocabulary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 随模组发的规则:硬规则,和出厂层。规则原文住在这里,主人看得见;出厂层主人改得了(写自己那一层,压过它),硬规则只有服主能关
 * ({@link PermissionConfig#hardRules})。
 *
 * <h2>硬规则</h2>
 * 主人答应不了的事:别人的东西。命中即拒,模式、主人层、任务期授权都解不开。
 *
 * <h2>出厂层</h2>
 * 读法:没人的随便挖、随便打;她自己的随便拆(装着东西的除外);主人的、说不清的、村子里的要问;别人的碰不得(硬规则)。
 * <ul>
 *   <li>allow 行写得比 ask 行窄,它们才轮得到 ask 表(同层 allow 先于 ask):带方块实体的(地牢的刷怪笼与战利品箱、蜂巢、
 *       可疑的沙子)不进任何拆方块的 allow 行,落到 {@code break(block_entity)} 去问;</li>
 *   <li>床、门、活板门、栅栏门不再单列:主人放的已经是 {@code owner},村子里的已经是 {@code structure(#minecraft:village)},
 *       自然生成的门只在结构里;</li>
 *   <li>右键一格里面没装东西的方块放行:开门、按按钮、睡床、开空容器不动别人的东西;</li>
 *   <li>右键一格装着东西的容器,问的就是"能不能动里面的东西"——打开之后往外拿不再另问:主人的、她自己的放行(她的设计就是用主人的
 *       工作台熔炉箱子;主人想管就加 {@code ask use_block(contents & owner)}),废弃结构里的战利品箱放行,村子里的、自然里的、
 *       说不清是谁的没有一行说到,去问;别人的是硬规则;</li>
 *   <li>执行只读或只说话的指令:帮助、在线名单、动作消息、私信、队伍消息、种子、随机数。别名随根名认
 *       ({@code tell}、{@code w} 归 {@code msg}),见 {@link Action#command}。其余指令没有一行说到,照旧问。</li>
 * </ul>
 */
public final class FactoryRules {

    private FactoryRules() {}

    /** 硬规则原文:都是拒绝。 */
    public static final List<String> HARD = List.of(
            "break(other)",
            "place(other)",
            "use_block(other & contents)",
            "attack(other)",
            "use_entity(other)",
            "place(hazard_item & near(other))");

    /** 出厂 allow 表原文。 */
    public static final List<String> ALLOW = List.of(
            "break(nature & !block_entity)",
            "break(self & !contents)",
            "break(structure & !structure(#minecraft:village) & !block_entity)",
            "place(!hazard_item)",
            "place(hazard_item & !near(owner) & !near(other))",
            "attack(hostile)",
            "attack(nature & !named & !villager)",
            "use_block(!contents)",
            "use_block(contents & owner)",
            "use_block(contents & self)",
            "use_block(contents & structure & !structure(#minecraft:village))",
            "use_entity(nature)",
            "use_entity(self)",
            "command(help)",
            "command(list)",
            "command(me)",
            "command(msg)",
            "command(teammsg)",
            "command(seed)",
            "command(random)");

    /** 出厂 ask 表原文。同一个动作命中几行时第一行作数。 */
    public static final List<String> ASK = List.of(
            "break(owner)",
            "break(unknown)",
            "break(structure(#minecraft:village))",
            "break(block_entity)",
            "break(self & contents)",
            "attack(owner)",
            "attack(self)",
            "attack(named)",
            "attack(villager)",
            "use_entity(owner)",
            "drop(*)",
            "place(hazard_item & near(owner))");

    /**
     * 原文按一份词表解析出来的规则。原文里有登记的词({@link RuleWord}),词是活的——插件装上、撤回——所以解析的结果跟着词表走:
     * 登记着的名字变了就重新解析。
     *
     * @param words 解析时登记着的名字
     */
    private record Parsed(Set<String> words, List<Rule> hard, RuleSet layer) {}

    private static volatile Parsed parsed;

    /** 硬规则;服主关掉({@link PermissionConfig#hardRules})时调用方传空表。 */
    public static List<Rule> hard() {
        return parsed().hard;
    }

    /** 出厂层:deny 空;allow 见 {@link #ALLOW};ask 见 {@link #ASK}。 */
    public static RuleSet layer() {
        return parsed().layer;
    }

    private static Parsed parsed() {
        Map<String, RuleWord> words = Groups.registered();
        Parsed now = parsed;
        if (now == null || !now.words.equals(words.keySet())) {
            Vocabulary vocabulary = Groups.vocabulary(words);
            now = new Parsed(Set.copyOf(words.keySet()), rules("hard", HARD, vocabulary),
                    new RuleSet(List.of(), rules("factory ask", ASK, vocabulary), rules("factory allow", ALLOW, vocabulary)));
            parsed = now;
        }
        return now;
    }

    /**
     * 一张表的原文解析成规则。写着此刻没人登记的词的那一行不成规则:记一条错误日志、不进表,和存档里解析不了的行同一个办法
     * (登记它的插件没装上时,那一行说的东西没人认得)。
     */
    private static List<Rule> rules(String table, List<String> texts, Vocabulary vocabulary) {
        List<Rule> out = new ArrayList<>();
        for (String text : texts) {
            try {
                out.add(Rule.parse(text, vocabulary));
            } catch (Rule.Mistake mistake) {
                Constants.LOG.error("[numen-permission] the {} rule {} is left out: {}", table, text, mistake.getMessage());
            }
        }
        return List.copyOf(out);
    }
}
