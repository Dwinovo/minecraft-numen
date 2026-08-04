package com.dwinovo.numen.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 名册的决策层——"哪只同伴还存在"这个问题的唯一答案出处。
 *
 * <h2>为什么单独拎出来</h2>
 * 这层<b>不碰 Minecraft</b>:全是 UUID、字符串、时间戳。同伴的增删改查从前散在
 * {@code Companions}/{@code DismissRequestPayload}/客户端处理体里,每一处都要 MinecraftServer
 * 才跑得起来,于是<b>一条都没测过</b>——真机上"遣散会误删数据"的 bug 正是从这个测不到的
 * 缝里长出来的。判断规则搬到这儿,就能拿普通单测把每条边界钉死。
 *
 * <h2>两条规则</h2>
 * <ol>
 *   <li>{@link #build} —— 名册来自<b>注册表</b>(持久、权威:谁存在),不是玩家列表
 *       (瞬时:谁此刻站在世界里)。死了、休眠、正等复活的同伴<b>都在名册上</b>,
 *       因为她们确实还存在。</li>
 *   <li>{@link #orphans} —— "不在名册上"只在<b>同一个世界内</b>才等于"被遣散了"。
 *       跨世界的家一律不碰:换个存档进去,别的世界的同伴当然不在这份名册上,
 *       那不叫被遣散。</li>
 * </ol>
 */
public final class CompanionRoster {

    /** 一 tick 二十分之一秒。 */
    public static final long MS_PER_TICK = 50L;

    /** {@link Line#respawnInMs()} 的活着取值。 */
    public static final long ALIVE = -1L;

    private CompanionRoster() {}

    /** 注册表的一条,剥到决策要用的三个字段({@code diedAtTick <= 0} = 活着)。 */
    public record Row(UUID uuid, String name, long diedAtTick) {}

    /** 发给主人的一行。{@code respawnInMs} 为 {@link #ALIVE} = 活着/休眠;
     *  ≥0 = 死了,还要这么久复活(0 = 时候到了,正等一个安全落点)。 */
    public record Line(UUID uuid, String name, long respawnInMs) {
        public boolean dead() {
            return respawnInMs >= 0;
        }
    }

    /**
     * 注册表 → 名册。按名字排序:注册表是 HashMap,不排的话面板上同伴的顺序
     * 每次推送都可能变。
     */
    public static List<Line> build(Collection<Row> rows, long nowTick, long respawnDelayTicks) {
        List<Line> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new Line(r.uuid(), r.name(), respawnInMs(r.diedAtTick(), nowTick, respawnDelayTicks)));
        }
        out.sort(Comparator.comparing(Line::name).thenComparing(l -> l.uuid().toString()));
        return out;
    }

    /**
     * 主人名下叫这个名字的同伴,没有则 null——<b>召唤的幂等就靠它</b>:同名就复用,
     * 不铸新 UUID。(早年每次召唤都铸新 UUID,同名分身全都会在登录时一起复活。)
     *
     * <p>万一有重名(老版本留下的),取定序后的第一个:注册表是 HashMap,不定序的话
     * 同一句"召唤小焰"今天复用这只、明天复用那只,主人会觉得同伴精神分裂。
     */
    public static UUID findByName(Collection<Row> rows, String name) {
        if (name == null) {
            return null;
        }
        UUID best = null;
        for (Row r : rows) {
            if (!name.equals(r.name())) {
                continue;
            }
            if (best == null || r.uuid().toString().compareTo(best.toString()) < 0) {
                best = r.uuid();
            }
        }
        return best;
    }

    /** 还有多久复活;活着返回 {@link #ALIVE}。时候已到但还没落地的返回 0(不返负数)。 */
    public static long respawnInMs(long diedAtTick, long nowTick, long respawnDelayTicks) {
        if (diedAtTick <= 0L) {
            return ALIVE;
        }
        long leftTicks = respawnDelayTicks - (nowTick - diedAtTick);
        return leftTicks <= 0L ? 0L : leftTicks * MS_PER_TICK;
    }

    /**
     * 该删的家:<b>属于这个世界</b>、却已不在名册上的。
     *
     * <p>世界对不上的、没标世界的,一律不删——"不在名册上"的原因太多了
     * (换了存档、还没收到名册、旧版本迁移过来的数据),只有"确定属于这个世界
     * 而这个世界说她不在了"才是真的被遣散。宁可留孤儿也不删错:孤儿扫一遍就没了,
     * 删错的会话找不回来。
     *
     * @param homeWorlds 客户端每个家目录 → 它标着的世界 id(没标的给 null)
     * @param currentWorldId 当前世界的 id;null/空 = 还不知道在哪儿,一个都不删
     * @param onRoster 这份名册上的同伴
     */
    public static List<UUID> orphans(Map<UUID, String> homeWorlds, String currentWorldId,
                                     Set<UUID> onRoster) {
        List<UUID> out = new ArrayList<>();
        if (currentWorldId == null || currentWorldId.isBlank()) {
            return out;
        }
        for (Map.Entry<UUID, String> e : homeWorlds.entrySet()) {
            if (!currentWorldId.equals(e.getValue())) {
                continue;   // 别的世界的家,或者没标过世界的旧数据:不归这次对账管
            }
            if (!onRoster.contains(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
