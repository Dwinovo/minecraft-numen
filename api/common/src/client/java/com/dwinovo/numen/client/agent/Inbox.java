package com.dwinovo.numen.client.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 收件箱(宪法 §4):主人的话与世界事件的统一进箱口——条目、落盘账本
 * ({@link InboxJournal})、年龄标注、倒箱拼接全在这里。三态路由(什么时候
 * 倒箱、什么输入配开轮)不在这里:那需要大脑的状态,是
 * {@link EntityAgentLoop} 的事。
 *
 * <p>事件与主人话分桶存:主人按停止清的是被取代的<em>指令</em>,永远不清
 * <em>事实</em>。<b>死亡什么都不清</b>——每条都盖着时间戳,模型自己看得出
 * "捡到铁矿"发生在"你死了、物品掉落"之前,不需要我们替它判断哪些信息过期。
 *
 * <p>倒箱顺序固定:事件按到达序在前,主人的话压轴。push 即整本落盘,
 * 倒箱/清除即清账,跨会话不失忆。
 *
 * <h2>urgent 不变量</h2>
 * urgent 事件<b>不在箱里过夜</b>:它一进来就该带着箱里一切开一轮。开轮的时机
 * 由 {@link EntityAgentLoop} 决定(回合进行中只能等协议边界),所以这里只负责
 * 如实记着 {@link #hasUrgent()},让上层问得到。
 */
final class Inbox {

    private record Entry(String text, long ts, boolean urgent) {}

    private final List<Entry> events = new ArrayList<>();
    private final List<Entry> prompts = new ArrayList<>();
    private final InboxJournal journal;

    Inbox(UUID entityUuid) {
        this.journal = InboxJournal.atFile(CompanionHome.inbox(entityUuid));
        // 上次会话没消费完的输入原样躺回(不开轮——旧闻不值得吵人,
        // 下一个轮子自然带上,倒箱时会标注年龄)。
        for (InboxJournal.Entry e : journal.load()) {
            boolean prompt = "prompt".equals(e.type());
            (prompt ? prompts : events).add(new Entry(e.text(), e.ts(), e.urgent()));
        }
    }

    void pushEvent(String xml, boolean urgent) {
        events.add(new Entry(xml, System.currentTimeMillis(), urgent));
        persist();
    }

    void pushPrompt(String wrapped) {
        prompts.add(new Entry(wrapped, System.currentTimeMillis(), false));
        persist();
    }

    /** 箱里有没有等着的 urgent 事件(它该逼出一次倾倒)。 */
    boolean hasUrgent() {
        for (Entry e : events) {
            if (e.urgent()) return true;
        }
        return false;
    }

    /** 最老那条事件躺了多久(毫秒);没有事件返回 0。 */
    long oldestEventAgeMs() {
        long oldest = Long.MAX_VALUE;
        for (Entry e : events) {
            if (e.ts() > 0 && e.ts() < oldest) oldest = e.ts();
        }
        return oldest == Long.MAX_VALUE ? 0L : Math.max(0L, System.currentTimeMillis() - oldest);
    }

    boolean isEmpty() {
        return events.isEmpty() && prompts.isEmpty();
    }

    int promptCount() {
        return prompts.size();
    }

    int eventCount() {
        return events.size();
    }

    /** GUI 待发列表(与倒箱顺序一致:事件在前,主人话在后)。 */
    List<String> snapshot() {
        List<String> all = new ArrayList<>();
        for (Entry e : events) all.add(e.text());
        for (Entry p : prompts) all.add(p.text());
        return List.copyOf(all);
    }

    /** 主人打断:清掉被取代的指令,事实(事件)保留。返回清掉的条数。 */
    int clearPrompts() {
        int n = prompts.size();
        prompts.clear();
        persist();
        return n;
    }

    /** 倒箱:全部条目按 事件 → 主人话 顺序取出(躺超十分钟的标注年龄),清空落盘。 */
    List<String> drain() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entry e : events) out.add(annotateAge(e, now));
        for (Entry p : prompts) out.add(annotateAge(p, now));
        events.clear();
        prompts.clear();
        persist();
        return out;
    }

    private void persist() {
        List<InboxJournal.Entry> all = new ArrayList<>();
        for (Entry e : events) all.add(new InboxJournal.Entry("event", e.text(), e.ts(), e.urgent()));
        for (Entry p : prompts) all.add(new InboxJournal.Entry("prompt", p.text(), p.ts(), false));
        journal.save(all);
    }

    /** 躺超过 10 分钟的输入消费时标注年龄——尤其是跨会话恢复的旧闻,模型该知道
     *  "主人不在时发生的",而不是当成刚发生的事去反应。 */
    private static String annotateAge(Entry e, long now) {
        long ageMs = e.ts() > 0 ? now - e.ts() : 0;
        if (ageMs < 10 * 60_000L) return e.text();
        String age = ageMs < 3_600_000L ? (ageMs / 60_000L) + "分钟"
                : ageMs < 86_400_000L ? (ageMs / 3_600_000L) + "小时"
                : (ageMs / 86_400_000L) + "天";
        return "[发生于约" + age + "前] " + e.text();
    }
}
