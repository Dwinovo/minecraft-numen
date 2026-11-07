package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.sdk.Doc;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 按玩家语言里的名字找物品({@code numen.inv.lookup}):名字取自物品注册表加当前的语言文件,所以只有客户端知道玩家看到的是什么字;
 * 这个函数在主人的客户端上执行,用的是那台客户端的语言。
 */
public final class ItemNames {

    /** 一次交回的物品条数上限:答复要装进脚本与大模型的上下文;超出的条数在 {@link Matches#omitted} 里如实说。 */
    public static final int MAX_MATCHES = 20;

    private ItemNames() {}

    /** 一样名字对得上的物品。 */
    @Doc("An item whose name matches.")
    public record Match(@Doc("The item id; every numen.inv function takes it.") String item,
                        @Doc("Its name in your owner's language, as they read it in the game.") String name) {}

    /** 一次查询的结果。 */
    @Doc("The items whose name matches, best match first.")
    public record Matches(List<Match> items,
                          @Doc("How many more matched but are not listed; absent when all are listed.")
                          Optional<Integer> omitted) {}

    /** 在注册表里的每样物品中找名字(或 id)含这段字的,用此刻这台机器的语言。 */
    public static Matches find(String query) {
        return find(query, BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR)
                .map(item -> new Match(BuiltInRegistries.ITEM.getKey(item).toString(),
                        item.getDescription().getString())));
    }

    /**
     * 对着一串(id, 名字)排出最贴的:名字整个就是这段字、名字以它开头、名字里含它、id 里含它,依次靠前;同一档按名字、id 排。
     * 大小写不计。
     */
    static Matches find(String query, Stream<Match> candidates) {
        String wanted = query.strip().toLowerCase(Locale.ROOT);
        List<Match> all = candidates
                .filter(m -> rank(wanted, m) >= 0)
                .sorted(Comparator.<Match>comparingInt(m -> rank(wanted, m)).thenComparing(Match::name)
                        .thenComparing(Match::item))
                .toList();
        int omitted = Math.max(0, all.size() - MAX_MATCHES);
        return new Matches(all.subList(0, all.size() - omitted),
                omitted == 0 ? Optional.empty() : Optional.of(omitted));
    }

    /** 0 名字整个是它、1 名字以它开头、2 名字含它、3 id 含它;对不上是 -1,空的一段字什么也对不上。 */
    private static int rank(String wanted, Match m) {
        if (wanted.isEmpty()) {
            return -1;
        }
        String name = m.name().toLowerCase(Locale.ROOT);
        if (name.equals(wanted)) {
            return 0;
        }
        if (name.startsWith(wanted)) {
            return 1;
        }
        if (name.contains(wanted)) {
            return 2;
        }
        return m.item().toLowerCase(Locale.ROOT).contains(wanted) ? 3 : -1;
    }
}
