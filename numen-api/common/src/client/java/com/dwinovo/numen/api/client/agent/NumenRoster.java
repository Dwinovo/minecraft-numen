package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.entity.Attendance;
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
     * 一只存在的同伴。{@code creative} 是服务端推来的此刻游戏模式,编辑卡的模式格显示当前值用。{@code outside} 是她被留在外面的
     * 状态与原因(主人让她下线、服务器把她请出去):留在外面的不在世界里,等主人再召唤。
     */
    public record Entry(UUID uuid, String name, boolean creative, Attendance.Outside outside) {

        /** 在册的一行,没有被留在外面。 */
        public Entry(UUID uuid, String name) {
            this(uuid, name, false, Attendance.Outside.NONE);
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

    /** 她被留在外面的状态与原因;不在名册上或没被留在外面是 {@link Attendance.Outside#NONE}。 */
    public Attendance.Outside outside(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? Attendance.Outside.NONE : e.outside();
    }
}
