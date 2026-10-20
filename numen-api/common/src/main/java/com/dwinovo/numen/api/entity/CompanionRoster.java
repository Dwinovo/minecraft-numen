package com.dwinovo.numen.api.entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 名册的决策层——"哪只同伴还存在"这个问题的唯一答案出处。
 *
 * <h2>为什么单独拎出来</h2>
 * 这层<b>不碰 Minecraft</b>:全是 UUID、字符串、时间戳,所以每条边界都能拿普通单测钉死。
 * 判断散在各处的话,每一处都要 MinecraftServer 才跑得起来,也就一条都测不到——而
 * "谁还存在"判错一次的代价是删掉主人的会话数据。
 *
 * <h2>规则</h2>
 * {@link #build} —— 名册来自<b>注册表</b>(持久、权威:谁存在),不是玩家列表
 * (瞬时:谁此刻站在世界里)。休眠的同伴<b>也在名册上</b>,因为她们确实还存在。
 */
public final class CompanionRoster {

    private CompanionRoster() {}

    /** 注册表的一条,剥到决策要用的两个字段。 */
    public record Row(UUID uuid, String name) {}

    /**
     * 注册表 → 名册。按名字排序:注册表是 HashMap,不排的话面板上同伴的顺序
     * 每次推送都可能变。
     */
    public static List<Row> build(Collection<Row> rows) {
        List<Row> out = new ArrayList<>(rows);
        out.sort(Comparator.comparing(Row::name).thenComparing(r -> r.uuid().toString()));
        return out;
    }

    /**
     * 主人名下叫这个名字的同伴,没有则 null——<b>召唤的幂等就靠它</b>:同名就复用,
     * 不铸新 UUID,否则每喊一次"召唤小焰"就多一只同名分身,登录时一起复活。
     *
     * <p>万一有重名(迁移过来的旧数据),取定序后的第一个:注册表是 HashMap,不定序的话
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
}
