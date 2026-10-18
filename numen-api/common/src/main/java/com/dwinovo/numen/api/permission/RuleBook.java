package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一位主人此刻受着的规则,按判定顺序的三层摊开:硬规则、主人层、出厂层,每一行带来源与命中账。命令
 * {@code /numen permission rules list}、她读规则的 {@code numen.permission.rules()} 和面板的规则页读的是这同一份,
 * 各自只管把它排成自己的样子。
 *
 * @param hardOn  服主有没有开着硬规则;关着时 {@code hard} 是空的
 * @param hard    硬规则,都是 deny
 * @param owner   主人层,按表分,每张表里按写的先后
 * @param factory 出厂层,按表分(deny 空)
 */
public record RuleBook(boolean hardOn, List<Line> hard, Map<Verdict.Kind, List<Line>> owner,
                       Map<Verdict.Kind, List<Line>> factory) {

    /**
     * 一行规则。
     *
     * @param table  在哪张表,也就是它给的答复
     * @param source 谁写的;硬规则与出厂层随模组发,为 null
     * @param note   写的人说的理由;没有为空串
     * @param hit    命中账
     */
    public record Line(Verdict.Kind table, Rule rule, PermissionStore.Source source, String note,
                       PermissionStore.Hit hit) {

        /** 她写的。 */
        public boolean byHer() {
            return source == PermissionStore.Source.HER;
        }
    }

    /**
     * @param store     这位主人的存档;还没有主人时为 null,主人层是空的
     * @param hardRules 服主开不开硬规则({@link PermissionConfig#hardRules})
     */
    public static RuleBook of(PermissionStore store, boolean hardRules) {
        List<Line> hard = new ArrayList<>();
        if (hardRules) {
            for (Rule rule : FactoryRules.hard()) {
                hard.add(new Line(Verdict.Kind.DENY, rule, null, "", hitsOf(store, Verdict.HARD, Verdict.Kind.DENY, rule)));
            }
        }
        Map<Verdict.Kind, List<Line>> owner = new EnumMap<>(Verdict.Kind.class);
        Map<Verdict.Kind, List<Line>> factory = new EnumMap<>(Verdict.Kind.class);
        for (Verdict.Kind table : Verdict.Kind.values()) {
            List<Line> mine = new ArrayList<>();
            if (store != null) {
                for (PermissionStore.Row row : store.rows(table)) {
                    mine.add(new Line(table, row.rule(), row.source(), row.note(),
                            store.hitsOf(Layers.OWNER, table, row.rule())));
                }
            }
            owner.put(table, List.copyOf(mine));
            factory.put(table, FactoryRules.layer().table(table).stream()
                    .map(rule -> new Line(table, rule, null, "", hitsOf(store, Layers.FACTORY, table, rule)))
                    .toList());
        }
        return new RuleBook(hardRules, List.copyOf(hard), owner, factory);
    }

    private static PermissionStore.Hit hitsOf(PermissionStore store, int layer, Verdict.Kind table, Rule rule) {
        return store == null ? PermissionStore.Hit.NEVER : store.hitsOf(layer, table, rule);
    }

    /** 最近一次命中({@code lastMillis})离现在多久,压成一个短词:{@code 12s}、{@code 5m}、{@code 3h}、{@code 2d}。 */
    public static String ago(long lastMillis, long nowMillis) {
        long seconds = Math.max(0, (nowMillis - lastMillis) / 1000);
        return seconds < 90 ? seconds + "s" : seconds < 5400 ? seconds / 60 + "m"
                : seconds < 172800 ? seconds / 3600 + "h" : seconds / 86400 + "d";
    }
}
