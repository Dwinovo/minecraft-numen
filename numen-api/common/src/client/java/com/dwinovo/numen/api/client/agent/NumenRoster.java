package com.dwinovo.numen.api.client.agent;

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
 * 名册由服务端从持久的注册表算出来({@code CompanionListPayload}),休眠的同伴<b>也在册</b>:
 * 她们确实还存在,只是此刻不在世界里。所以"不在名册上"
 * 只有一个意思——<b>被永久遣散了</b>,客户端据此删掉她的本地数据。
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
     * 一只存在的同伴。{@code creative} 是服务端推来的此刻游戏模式,编辑卡的模式格显示当前值用。{@code removedFor} 非空 = 她被请出了
     * 服务器、不在世界里(值是理由)。
     */
    public record Entry(UUID uuid, String name, boolean creative, String removedFor) {

        /** 在册的一行,没有被请出去。 */
        public Entry(UUID uuid, String name) {
            this(uuid, name, false, "");
        }

        public boolean removed() {
            return !removedFor.isEmpty();
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

    /** 被请出了服务器、等主人再召唤? */
    public boolean isRemoved(UUID uuid) {
        Entry e = entries.get(uuid);
        return e != null && e.removed();
    }

    /** 被请出去时说的理由;不是被请出去的返回空串。 */
    public String removedFor(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? "" : e.removedFor();
    }
}
