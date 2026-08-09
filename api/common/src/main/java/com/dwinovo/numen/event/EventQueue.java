package com.dwinovo.numen.event;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同伴的输入队列——主人的话与世界事件<b>共用</b>的这一个。
 *
 * <h2>它只遵守自己的规则</h2>
 * 不认识 Minecraft,不认识 agent 死没死,不认识"回合"是什么,也不认识条目的类型
 * (那是 {@link EventTypes} 的表)。它只回答一个问题:<b>现在该不该排空</b>。
 *
 * <pre>
 * 有急件 + 没锁  → 排空
 * 有急件 + 锁着  → 等锁开
 * 没急件         → 看条数、看时长
 * </pre>
 *
 * <p>没有第四条。谁发的、什么类型、她当时在干嘛——一律不看。
 *
 * <h2>锁</h2>
 * 多持有者:{@link #lock}/{@link #unlock} 收一个不透明字符串,全部松手才算开。
 * 队列不知道那些字符串是什么意思(她死了?外接大脑接管了?),只知道有人锁着。
 * 锁只管<b>出</b>,不管进、也不管清——主人按停止清指令跟她能不能说话无关。
 *
 * <h2>"排空"是"到点就走",不是"立刻发出"</h2>
 * {@link #shouldDrain} 只读状态,可以反复问、答案一致。上层因为协议原因(不能往
 * assistant 的 tool_calls 中间插 user 消息)排不成,下一 tick 再问就是了——
 * <b>不存在"错过的排空"</b>,也就不需要记住"我刚才想排空"这种会出错的状态。
 *
 * <h2>上限</h2>
 * {@value #DEFAULT_CAP} 条,满了丢最老的并记账,排空时如实补一句。锁可能开很久
 * (外接大脑能开一整天),不设上限就会涨到把上下文撑爆;但丢弃不能无声无息——
 * 主人得知道自己看到的是全部还是残片。
 *
 * <p>纯 JVM。落盘由 {@link Journal} 注入,线程约束由持有者负责。
 */
public final class EventQueue {

    /** 队列默认上限;服务端暂存与客户端收件共用这一个数。 */
    public static final int DEFAULT_CAP = 200;

    /** 一条待处理的输入。{@code type} 查 {@link EventTypes};{@code ts} 是真实时间。 */
    public record Entry(String type, String text, long ts, boolean urgent) {}

    /** 落盘口。队列不知道自己被存成 JSON 还是 NBT,存在哪。 */
    public interface Journal {
        List<Entry> load();

        void save(List<Entry> entries);

        /** 不落盘的实现(服务端把整份状态交给存档系统时用)。 */
        Journal NONE = new Journal() {
            @Override public List<Entry> load() {
                return List.of();
            }

            @Override public void save(List<Entry> entries) {
            }
        };
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> locks = new LinkedHashSet<>();
    private final Journal journal;
    private final int cap;
    private int dropped;

    public EventQueue(Journal journal) {
        this(journal, DEFAULT_CAP);
    }

    public EventQueue(Journal journal, int cap) {
        this.journal = journal == null ? Journal.NONE : journal;
        this.cap = Math.max(1, cap);
        entries.addAll(this.journal.load());
    }

    // ---- 进 ----

    /** 收一条。满了丢最老的并记账。 */
    public void push(String type, String text, long now, boolean urgent) {
        if (text == null || text.isBlank()) {
            return;
        }
        entries.add(new Entry(type, text, now, urgent));
        while (entries.size() > cap) {
            entries.remove(0);
            dropped++;
        }
        journal.save(entries);
    }

    // ---- 锁 ----

    /** 上锁。同一个持有者重复上锁是幂等的。 */
    public void lock(String holder) {
        if (holder != null && locks.add(holder)) {
            journal.save(entries);
        }
    }

    /** 松手。没上过的锁静默忽略;全部松手才算开。 */
    public void unlock(String holder) {
        if (holder != null && locks.remove(holder)) {
            journal.save(entries);
        }
    }

    public boolean locked() {
        return !locks.isEmpty();
    }

    /** 谁锁着(给日志看)。 */
    public Set<String> lockHolders() {
        return Set.copyOf(locks);
    }

    // ---- 出 ----

    /**
     * 现在该不该排空。
     *
     * @param now   真实时间(墙上时钟——游戏刻在单机退出时是冻结的,拿它算"躺了多久"
     *              会以为什么都没老)
     * @param level 主动性档位 1~10,见 {@link #thresholdOf} / {@link #maxWaitMsOf}
     */
    public boolean shouldDrain(long now, int level) {
        if (locked() || entries.isEmpty()) {
            return false;
        }
        for (Entry e : entries) {
            if (e.urgent()) {
                return true;
            }
        }
        return entries.size() >= thresholdOf(level) || oldestAgeMs(now) >= maxWaitMsOf(level);
    }

    /**
     * 取走全部<b>原始条目</b>并清空——转发用(服务端暂存 → 客户端队列)。
     *
     * <p>转发时不能渲染:渲染会把类型和时间戳压成一个字符串,收下的那一端就没法
     * 知道"这是三小时前的事"了,她会当成刚发生的去反应。
     *
     * <p>因为满了丢掉的条数在这里变成一条普通事件——丢弃可以,无声消失不行。
     */
    public List<Entry> takeEntries(long now) {
        List<Entry> out = new ArrayList<>(entries);
        // 按发生时间排：入队顺序不等于发生顺序（服务端离线出箱里攒的那批、
        // 死亡期间锁着攒下的那批，都是后来才进队的）。稳定排序，同时刻保持入队先后。
        out.sort(java.util.Comparator.comparingLong(Entry::ts));
        if (dropped > 0) {
            out.add(new Entry(EventTypes.EVENT, droppedNote(dropped), now, false));
            dropped = 0;
        }
        entries.clear();
        journal.save(entries);
        return out;
    }

    /** 全部取走并清空,按到达序渲染成给模型的字符串。躺超十分钟的标上年龄。 */
    /**
     * 倒出来拼给模型:<b>世界的事在前,主人的话在后</b>。
     *
     * <p>世界的事按发生时间排好、包进一个 {@code <events>} 里;主人说的话原样跟在后面。
     * 平铺着混排的话,模型得自己从一串杂物里理出时间线——而"她说了什么"跟"世界发生了
     * 什么"本来就是两种东西,读的顺序该是先看清发生了什么,再看主人要什么。
     *
     * <p>谁算哪一种由类型表说了算({@link EventTypes.Type#fromOwner}),这里不认 id。
     */
    public List<String> drain(long now) {
        List<Entry> taken = takeEntries(now);
        List<String> events = new ArrayList<>();
        List<String> owner = new ArrayList<>();
        for (Entry e : taken) {
            EventTypes.Type t = EventTypes.get(e.type());
            String rendered = t.toModel().apply(e.text());
            if (rendered == null || rendered.isBlank()) {
                continue;
            }
            (t.fromOwner() ? owner : events).add(annotateAge(rendered, e.ts(), now));
        }
        List<String> out = new ArrayList<>();
        if (!events.isEmpty()) {
            out.add("<events>\n" + String.join("\n", events) + "\n</events>");
        }
        out.addAll(owner);
        return out;
    }

    /** 溢出丢弃的说明文本——服务端暂存与客户端收件共用一句话。 */
    public static String droppedNote(int n) {
        return "<event kind=\"body_log\">期间还发生了约 " + n + " 件事,没能记下来</event>";
    }

    /**
     * 取走某一类的全部条目并清空它们;其余原样留着。
     *
     * <p>给"这一类不是拼给模型的文本"的消费者用——比如整理记忆:它到了安全点要做的是
     * 发起整理,不是往 user 消息里添一段话。队列不知道那意味着什么,只负责把该类交出去。
     *
     * @return 取走的条目,按入队顺序;一条都没有则空列表
     */
    public List<Entry> takeMatching(String type) {
        if (type == null) {
            return List.of();
        }
        List<Entry> taken = new ArrayList<>();
        entries.removeIf(e -> {
            if (!type.equals(e.type())) {
                return false;
            }
            taken.add(e);
            return true;
        });
        if (!taken.isEmpty()) {
            journal.save(entries);
        }
        return taken;
    }

    /** 主人打断:按类型表清掉该清的(指令),留下不该清的(事实)。返回清掉几条。 */
    public int clearInterrupted() {
        int before = entries.size();
        entries.removeIf(e -> EventTypes.get(e.type()).clearedByInterrupt());
        int n = before - entries.size();
        if (n > 0) {
            journal.save(entries);
        }
        return n;
    }

    // ---- 看 ----

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public int count(String type) {
        int n = 0;
        for (Entry e : entries) {
            if (e.type().equals(type)) n++;
        }
        return n;
    }

    public boolean hasUrgent() {
        for (Entry e : entries) {
            if (e.urgent()) return true;
        }
        return false;
    }

    /** 最老那条躺了多久;空队列返回 0。 */
    public long oldestAgeMs(long now) {
        long oldest = Long.MAX_VALUE;
        for (Entry e : entries) {
            if (e.ts() > 0 && e.ts() < oldest) oldest = e.ts();
        }
        return oldest == Long.MAX_VALUE ? 0L : Math.max(0L, now - oldest);
    }

    /** 因为满了被丢掉、还没报告过的条数。 */
    public int droppedCount() {
        return dropped;
    }

    /** 待发列表(给聊天流看):按类型表渲染,返回 null 的类型不出现。 */
    public List<String> chatPreview() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            String s = EventTypes.get(e.type()).chatPreview().apply(e.text());
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    /** 原始条目快照(落盘/转发用)。 */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    // ---- 档位 ----

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;
    /** 缺省档位:攒够几条说一次,既不聒噪也不装死。 */
    public static final int DEFAULT_LEVEL = 3;

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /** 攒够几条就说。就是档位本身:1 = 一有动静就说,10 = 攒够十条。 */
    public static int thresholdOf(int level) {
        return clampLevel(level);
    }

    /**
     * 攒到多久也要说。随档位拉长:1 档约 20 秒,3 档 3 分钟,10 档约 33 分钟。
     *
     * <p>少了这条,高档位就退化成永久沉默——阈值 10 而只发生了 3 件事,那 3 条会
     * 一直躺到主人下次开口。
     */
    public static long maxWaitMsOf(int level) {
        int lv = clampLevel(level);
        return (60_000L * lv * lv) / 3L;
    }

    /** 躺超十分钟的标注年龄——跨会话恢复的旧闻,模型该知道那是"主人不在时发生的"。 */
    private static String annotateAge(String text, long ts, long now) {
        long ageMs = ts > 0 ? Math.max(0L, now - ts) : 0L;
        if (ageMs < 10 * 60_000L) {
            return text;
        }
        String age = ageMs < 3_600_000L ? (ageMs / 60_000L) + "分钟"
                : ageMs < 86_400_000L ? (ageMs / 3_600_000L) + "小时"
                : (ageMs / 86_400_000L) + "天";
        return "[发生于约" + age + "前] " + text;
    }
}
