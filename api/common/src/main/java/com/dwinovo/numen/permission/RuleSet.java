package com.dwinovo.numen.permission;

import java.util.List;

/**
 * deny、ask、allow 三张表加两条熔断。规则是数据,这里只存和查,不判世界。
 *
 * <h2>查的顺序</h2>
 * deny → 熔断 → allow → ask → 都不中即放行。allow 排在 ask 前面,因为"允许并记住"存的是从
 * 某条 ask 行里抠出来的一条更细的 allow 行({@code allow break(placed & minecraft:cobblestone)}
 * 对着 {@code ask break(placed)}):ask 若先查,记住的规则永远轮不到。熔断排在 allow 前面,
 * 正是为了让 allow 盖不住它。
 */
public final class RuleSet {

    /**
     * 永远问、"允许并记住"也盖不住的两条:撤不回,模型犯一次错的代价太大。
     */
    public static final List<Rule> CIRCUIT_BREAKERS = List.of(
            Rule.parse("attack(owned)"),
            Rule.parse("break(block_entity & contents)"));

    /** 出厂 ask 表原文。 */
    public static final List<String> FACTORY_ASK = List.of(
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

    /**
     * 出厂默认:deny 空(领地裁决不是规则,是命中即 deny 的信号);ask 见 {@link #FACTORY_ASK};
     * allow 空——其余(自然方块、野生动物、敌对生物、自己的背包、开关门、开容器、从容器拿东西)
     * 由"都不中即放行"覆盖。床/门/活板门/栅栏门按标签列在 ask 里:关着的门执行器伸手开,拆掉
     * 它永远不该是寻路的自主决定。
     */
    public static RuleSet factory() {
        return FACTORY;
    }

    private static final RuleSet FACTORY = new RuleSet(List.of(),
            FACTORY_ASK.stream().map(Rule::parse).toList(), List.of());

    public List<Rule> deny() {
        return deny;
    }

    public List<Rule> ask() {
        return ask;
    }

    public List<Rule> allow() {
        return allow;
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
