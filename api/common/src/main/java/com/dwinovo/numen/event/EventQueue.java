package com.dwinovo.numen.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 同伴的输入队列——主人的话与世界事件<b>共用</b>的这一个。
 *
 * <h2>它只是台账,不是调度员</h2>
 * 不认识 Minecraft,不认识 agent 死没死,不认识"回合"是什么,也不认识条目的类型
 * (那是 {@link EventTypes} 的表)。它只回答一个问题:<b>现在熟没熟</b>。
 *
 * <pre>
 * 有急件  → 熟了
 * 没急件  → 看条数、看时长
 * </pre>
 *
 * <p>没有第三条。谁发的、什么类型、她当时在干嘛、消费者此刻方不方便——一律不看。
 * 消费者能不能来取(她死了?驾驶席在外接大脑手里?)是<b>消费者自己的停牌</b>,
 * 不在这里:队列没有锁,取件口({@link #takeWhile}/{@link #takeEntries})永远敞着,
 * 谁来取、什么时候取,由持有队列的人决定。
 *
 * <h2>急件叫醒:脉冲可以丢,电平不会骗</h2>
 * 急件落地时同步通知{@link #addUrgentListener 登记过的等待者}——叫的内容只是
 * "来问吧",不递事件。正确性从不依赖叫醒:{@link #hasUrgent}/{@link #shouldDrain}
 * 是随时可问、答案一致的状态;丢一次叫醒,消费者按自己的节律(内脑的 tick 心跳、
 * 外脑的下一次长轮询)一问就发现。
 *
 * <h2>"排空"是"到点就走",不是"立刻发出"</h2>
 * {@link #shouldDrain} 只读状态,可以反复问、答案一致。上层因为协议原因(不能往
 * assistant 的 tool_calls 中间插 user 消息)排不成,下一 tick 再问就是了——
 * <b>不存在"错过的排空"</b>,也就不需要记住"我刚才想排空"这种会出错的状态。
 *
 * <h2>上限</h2>
 * {@value #DEFAULT_CAP} 条,满了丢最老的并记账,排空时如实补一句。消费者可能很久
 * 不来取(外接大脑失联、她死着躺一晚上),不设上限就会涨到把上下文撑爆;但丢弃
 * 不能无声无息——主人得知道自己看到的是全部还是残片。
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
    /** 急件叫醒名单。回调在 push 的调用线程上同步执行,只该做"完成一个等待"这类轻动作。 */
    private final List<Runnable> urgentListeners = new ArrayList<>();
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
        if (urgent) {
            // 叫醒在入队落盘之后:等待者被叫起来一问,货一定已经在。
            // 拷贝一份再遍历,回调里摘自己是安全的。
            for (Runnable listener : List.copyOf(urgentListeners)) {
                listener.run();
            }
        }
    }

    // ---- 急件叫醒 ----

    /** 登记急件叫醒(叫"来问吧",不递事件)。 */
    public void addUrgentListener(Runnable listener) {
        if (listener != null) {
            urgentListeners.add(listener);
        }
    }

    /** 摘掉叫醒。没登记过的静默忽略。 */
    public void removeUrgentListener(Runnable listener) {
        urgentListeners.remove(listener);
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
        if (entries.isEmpty()) {
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
        flushDropped(out, now);
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
    /**
     * 把已经取出来的条目拼成给模型看的几段。
     *
     * <p>与"取"分开,是因为按顺序排空时只会取走开头一段({@link #takeWhile}),而渲染规则
     * 对取多少无关——两边共用这一份,不会说不到一块儿去。
     *
     * <p>{@code toModel} 回 null/空的条目在这里消失:那是类型表说"这条不是给模型看的文本"。
     */
    public static List<String> render(List<Entry> taken, long now) {
        List<Entry> ordered = new ArrayList<>(taken);
        ordered.sort(java.util.Comparator.comparingLong(Entry::ts));
        List<String> events = new ArrayList<>();
        List<String> owner = new ArrayList<>();
        for (Entry e : ordered) {
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
     * 按入队顺序取走<b>开头那一段</b>——一直取到第一条不满足 {@code keep} 的为止;
     * 那一条及其之后的原样留在队里。
     *
     * <p>队列因此能<b>按顺序</b>排空:遇到一条不该当文本处理的(比如整理记忆),前面的先
     * 走完,它留在队首等下一个安全点。没有插队,也就不用为以后每种新类型回答"它插不插队"。
     *
     * @return 取走的条目,按入队顺序;队首就不满足则空列表
     */
    public List<Entry> takeWhile(java.util.function.Predicate<Entry> keep, long now) {
        if (keep == null) {
            return List.of();
        }
        int n = 0;
        while (n < entries.size() && keep.test(entries.get(n))) {
            n++;
        }
        if (n == 0) {
            return List.of();
        }
        List<Entry> taken = new ArrayList<>(entries.subList(0, n));
        entries.subList(0, n).clear();
        flushDropped(taken, now);
        journal.save(entries);
        return taken;
    }

    /** 因为满了丢掉、还没报告过的条数变成一条普通事件——丢弃可以,无声消失不行。 */
    private void flushDropped(List<Entry> out, long now) {
        if (dropped > 0) {
            out.add(new Entry(EventTypes.EVENT, droppedNote(dropped), now, false));
            dropped = 0;
        }
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
