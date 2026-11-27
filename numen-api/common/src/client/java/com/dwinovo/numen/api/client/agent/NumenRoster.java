package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.entity.Attendance;
import com.dwinovo.numen.api.entity.Keeper;
import com.dwinovo.numen.api.sdk.EntityLabel;
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
     * 状态与原因(主人让她下线、服务器把她请出去):留在外面的不在世界里,等主人再召唤。{@code keepers} 是她的主人表:创建者在前,
     * 后面是分享给的人,按加进来的先后。
     */
    public record Entry(UUID uuid, String name, boolean creative, Attendance.Outside outside, List<Keeper> keepers) {

        public Entry {
            keepers = List.copyOf(keepers);
        }

        /** 在册的一行,没有被留在外面;主人表没有说。 */
        public Entry(UUID uuid, String name) {
            this(uuid, name, false, Attendance.Outside.NONE, List.of());
        }

        /** 创建她的那位玩家;主人表没有说是 {@code null}。 */
        public Keeper creator() {
            return keepers.isEmpty() ? null : keepers.get(0);
        }

        /** {@code person} 是不是她的创建者:删除、清空记忆、改主人表这些入口只给他(能不能办仍由服务端判)。 */
        public boolean createdBy(UUID person) {
            Keeper creator = creator();
            return creator != null && creator.uuid().equals(person);
        }

        /** 她不止一位主人(分享出去了,或是分享来的):说话的不止一位,每句得认得出是谁的。 */
        public boolean severalKeepers() {
            return keepers.size() > 1;
        }

        /** 对 {@code person} 来说她是分享来的:知道创建者是谁,而且不是他。 */
        public boolean sharedWith(UUID person) {
            return creator() != null && !createdBy(person);
        }

        /** 主人表上短码是这个的那一位({@code EntityLabel.code});没有是 {@code null}。 */
        public Keeper keeperCoded(String code) {
            for (Keeper keeper : keepers) {
                if (EntityLabel.code(keeper.uuid()).equals(code)) {
                    return keeper;
                }
            }
            return null;
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

    /** 这只同伴在册的那一行,不在名册上则 null。 */
    public Entry entry(UUID uuid) {
        return entries.get(uuid);
    }

    /**
     * 她的日志里一句主人的话标着的说话人({@code EntityLabel.shown} 的写法)是谁:她主人表上的那一位;已经不在表上的(说过话之后被移出了)
     * 照标记里的名字给,没带名字的写他的身份,UUID 由标记定(同一位永远同一个;没有他的皮肤,脸是默认的)。读不懂、名册上没有她是 null。
     */
    public Keeper speaker(UUID companion, String shown) {
        EntityLabel.ShownPlayer read = EntityLabel.readShown(shown);
        Entry entry = entries.get(companion);
        if (read == null || entry == null) {
            return null;
        }
        Keeper onTable = entry.keeperCoded(read.code());
        return onTable != null ? onTable
                : new Keeper(UUID.nameUUIDFromBytes(read.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                read.name().isEmpty() ? read.id() : read.name());
    }

    /**
     * 这句主人的话若是她的另一位主人说的,是那一位;{@code viewer} 自己说的、没记是谁说的(不是谁亲口说的)是 {@code null}。
     * 聊天流的气泡、左栏的最后一句、未读都按这一处认。
     *
     * @param from 记录里这句话的说话人({@code player@短码});没有是 {@code null}
     */
    public Keeper otherSpeaker(UUID companion, String from, UUID viewer) {
        Keeper said = from == null ? null : speaker(companion, from);
        return said == null || said.uuid().equals(viewer) ? null : said;
    }

    /** 这只同伴的主人表上不止一位;不在名册上不算。 */
    public boolean severalKeepers(UUID companion) {
        Entry entry = entries.get(companion);
        return entry != null && entry.severalKeepers();
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
