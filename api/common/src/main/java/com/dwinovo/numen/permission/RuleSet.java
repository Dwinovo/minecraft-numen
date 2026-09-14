package com.dwinovo.numen.permission;

import java.util.List;

/**
 * 一层规则:deny、allow、ask 三张表。规则是数据,这里只存和查,不判世界。裁决用两层——主人自己写的
 * ({@link PermissionStore}),和出厂的({@link #factory});怎么叠见 {@link Gate}。
 *
 * <h2>层内的顺序</h2>
 * deny → allow → ask。allow 排在 ask 前面,因为"允许并记住"存的是从某条 ask 行里抠出来的一条更细的
 * allow 行({@code allow break(placed & minecraft:cobblestone)} 对着 {@code ask break(placed)}):
 * ask 若先查,记住的规则永远轮不到。
 *
 * <p>于是出厂 allow 行必须写得比 ask 行窄——自然方块那一行把玩家放的、带方块实体的、床门活板门
 * 栅栏门都排除在外,它们才轮得到 ask 表。
 */
public final class RuleSet {

    /**
     * 出厂 allow 表原文:日常动作一行一行写明,主人看得见、改得了。
     * <ul>
     *   <li>挖自然方块——不是玩家放的、没有方块实体、不是床门活板门栅栏门;</li>
     *   <li>放不危险的东西,或者离玩家的东西远的危险物;</li>
     *   <li>打没主人、没名字、不是村民的(敌对生物与野生动物);</li>
     *   <li>开关门、开容器、按按钮;对没主人的实体右键;从容器拿东西。</li>
     * </ul>
     */
    public static final List<String> FACTORY_ALLOW = List.of(
            "break(!placed & !block_entity & !#minecraft:beds & !#minecraft:doors"
                    + " & !#minecraft:trapdoors & !#minecraft:fence_gates)",
            "place(!hazard_item)",
            "place(hazard_item & !near_placed)",
            "attack(!owned & !named & !villager)",
            "use_block(*)",
            "use_entity(!owned)",
            "take(*)");

    /**
     * 出厂 ask 表原文。同一个动作命中几行时第一行作数,所以更具体的在前:装着东西的容器先于
     * 玩家放的,有主人的先于有名字的——清单上标出撤不回的那一类靠它。
     */
    public static final List<String> FACTORY_ASK = List.of(
            "break(block_entity & contents)",
            "break(placed)",
            "break(block_entity)",
            "break(#minecraft:beds)",
            "break(#minecraft:doors)",
            "break(#minecraft:trapdoors)",
            "break(#minecraft:fence_gates)",
            "attack(owned)",
            "attack(named)",
            "attack(villager)",
            "drop(*)",
            "place(hazard_item & near_placed)");

    private final List<Rule> deny;
    private final List<Rule> ask;
    private final List<Rule> allow;

    public RuleSet(List<Rule> deny, List<Rule> ask, List<Rule> allow) {
        this.deny = List.copyOf(deny);
        this.ask = List.copyOf(ask);
        this.allow = List.copyOf(allow);
    }

    /** 三张表都空:主人还没写过任何一行。 */
    public static final RuleSet EMPTY = new RuleSet(List.of(), List.of(), List.of());

    /** 出厂默认:deny 空;allow 见 {@link #FACTORY_ALLOW};ask 见 {@link #FACTORY_ASK}。 */
    public static RuleSet factory() {
        return FACTORY;
    }

    private static final RuleSet FACTORY = new RuleSet(List.of(),
            FACTORY_ASK.stream().map(Rule::parse).toList(),
            FACTORY_ALLOW.stream().map(Rule::parse).toList());

    public List<Rule> deny() {
        return deny;
    }

    public List<Rule> ask() {
        return ask;
    }

    public List<Rule> allow() {
        return allow;
    }

    /** 按表名取一张表:表以它的行给出的答复命名。 */
    public List<Rule> table(Verdict.Kind table) {
        return switch (table) {
            case DENY -> deny;
            case ASK -> ask;
            case ALLOW -> allow;
        };
    }

    /** 换掉其中一张表的一份新规则层;本层不变。 */
    public RuleSet withTable(Verdict.Kind table, List<Rule> rows) {
        return new RuleSet(table == Verdict.Kind.DENY ? rows : deny, table == Verdict.Kind.ASK ? rows : ask,
                table == Verdict.Kind.ALLOW ? rows : allow);
    }

    public static Rule firstMatch(List<Rule> table, Action action, Facts facts) {
        for (Rule r : table) {
            if (r.matches(action, facts)) {
                return r;
            }
        }
        return null;
    }
}
