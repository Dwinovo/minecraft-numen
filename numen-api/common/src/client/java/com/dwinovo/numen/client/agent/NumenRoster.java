package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.entity.CompanionRoster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边"我有哪些同伴、她们什么状态"的<b>唯一</b>镜像——G 面板、快捷轮盘、
 * 外接大脑入口都读它。
 *
 * <h2>存在,而不是活着</h2>
 * 名册由服务端从持久的注册表算出来({@code CompanionListPayload}),死了、正等复活、
 * 休眠的同伴<b>都在册</b>:她们确实还存在,只是此刻不在世界里。所以"不在名册上"
 * 只有一个意思——<b>被永久遣散了</b>,客户端据此删掉她的本地数据。
 *
 * <p>死亡倒计时也住在这儿({@link Entry#respawnAtMs}),不另开一份缓存:同一个问题
 * 两处答案,迟早对不上。上一版把倒计时单独存,主人在死亡窗口里重登就丢状态。
 *
 * <h2>换存档必须清空</h2>
 * {@link #clear()} 在断开连接时调。名册残留会让下一个存档的第一份名册看起来
 * "少了一堆同伴"——而少了就意味着删数据。
 *
 * <h2>Threading</h2>
 * Client main thread only.
 */
public final class NumenRoster {

    /**
     * 一只存在的同伴。{@code respawnAtMs} 为 0 = 活着;否则是复活的绝对时刻
     * (毫秒),面板据此画倒计时——存绝对时刻而不是剩余量,两次推送之间也走得动。
     * {@code creative} 是服务端推来的此刻游戏模式,编辑卡的模式格显示当前值用。
     */
    public record Entry(UUID uuid, String name, long respawnAtMs, boolean creative) {

        /** 活着的一行。 */
        public Entry(UUID uuid, String name) {
            this(uuid, name, 0L, false);
        }

        public boolean dead() {
            return respawnAtMs > 0L;
        }

        /** 还有多久复活(不小于 0);活着返回 -1。 */
        public long remainingMs() {
            return respawnAtMs <= 0L ? -1L : Math.max(0L, respawnAtMs - System.currentTimeMillis());
        }
    }

    private static NumenRoster instance;

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private String worldId;

    private NumenRoster() {}

    public static NumenRoster instance() {
        if (instance == null) {
            instance = new NumenRoster();
        }
        return instance;
    }

    /** 整份替换(服务端每次推的都是完整名册),并记下它属于哪个世界。 */
    public void replaceAll(String worldId, List<Entry> snapshot) {
        this.worldId = worldId == null || worldId.isBlank() ? null : worldId;
        entries.clear();
        for (Entry e : snapshot) {
            entries.put(e.uuid(), e);
        }
    }

    /** 当前名册属于哪个世界;还没收到过名册则 null。 */
    public String worldId() {
        return worldId;
    }

    /** 断开连接:名册连同世界身份一起作废。 */
    public void clear() {
        entries.clear();
        worldId = null;
    }

    /** 所有存在的同伴,服务端给的顺序(按名字排好的)。 */
    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public int size() {
        return entries.size();
    }

    /** 这只同伴的显示名,不在名册上则 null。 */
    public String name(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? null : e.name();
    }

    /** 死了、正在等复活? */
    public boolean isDead(UUID uuid) {
        Entry e = entries.get(uuid);
        return e != null && e.dead();
    }

    /** 还有多久复活(不小于 0);活着或不在名册上返回 -1。 */
    public long remainingMs(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? -1L : e.remainingMs();
    }

    /** 名册行 → 客户端条目:把"还有多久"换算成绝对时刻。 */
    public static Entry toEntry(UUID uuid, String name, long respawnInMs, boolean creative) {
        return new Entry(uuid, name,
                respawnInMs < 0L ? 0L : System.currentTimeMillis() + respawnInMs, creative);
    }
}
