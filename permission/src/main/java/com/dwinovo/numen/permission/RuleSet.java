package com.dwinovo.numen.permission;

import java.util.List;

/**
 * 一层规则:deny、allow、ask 三张表。规则是数据,这里只存和查,不判世界。怎么叠见 {@link Policy}。
 *
 * <p>层内:deny 先于一切(见 {@link Policy});allow 排在 ask 前面,因为"允许并记住"存的是从某条 ask 行里抠出来的
 * 一条更细的 allow 行({@code allow break(owner & minecraft:cobblestone)} 对着 {@code ask break(owner)}):
 * ask 若先查,记住的规则永远轮不到。于是 allow 行必须写得比 ask 行窄——它们才轮得到 ask 表。书写顺序不影响结果。
 */
public final class RuleSet {

    /** 三张表都空。 */
    public static final RuleSet EMPTY = new RuleSet(List.of(), List.of(), List.of());

    private final List<Rule> deny;
    private final List<Rule> ask;
    private final List<Rule> allow;

    public RuleSet(List<Rule> deny, List<Rule> ask, List<Rule> allow) {
        this.deny = List.copyOf(deny);
        this.ask = List.copyOf(ask);
        this.allow = List.copyOf(allow);
    }

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

    /** 表里第一条对这个动作成立的规则;没有为 null。 */
    public static Rule firstMatch(List<Rule> table, Act act) {
        for (Rule r : table) {
            if (r.matches(act)) {
                return r;
            }
        }
        return null;
    }
}
