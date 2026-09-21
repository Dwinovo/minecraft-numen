package com.dwinovo.numen.agent.conversation;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一个会话的记录从成员各自的日志里归并出来。守的是:主人一句话的 N 份副本归成一条(靠发言号,
 * 不靠时间戳),而两句真的不同的话不会被合掉;别的会话的行不混进来;回复各标各的说话人。
 */
class TranscriptTest {

    private static final UUID YOU = UUID.randomUUID();
    private static final UUID LAN = UUID.randomUUID();

    private static ConvoLog.Line owner(long ts, String conv, int turn, String said, String audience) {
        return new ConvoLog.Line(ts, conv, new ConvoState.Msg.User(
                "<query>" + said + "</query>\n" + Audience.line(turn, audience)));
    }

    private static ConvoLog.Line reply(long ts, String conv, String said) {
        return new ConvoLog.Line(ts, conv, new ConvoState.Msg.Assistant(new AssistantTurn(said, List.of(), null)));
    }

    private static Map<UUID, List<ConvoLog.Line>> logs(List<ConvoLog.Line> you, List<ConvoLog.Line> lan) {
        Map<UUID, List<ConvoLog.Line>> m = new LinkedHashMap<>();
        m.put(YOU, you);
        m.put(LAN, lan);
        return m;
    }

    /** 各自盖各自的时间戳、audience 也各不相同——同一句话的两份副本只画一条,留最早的那份。 */
    @Test
    void copiesOfTheOwnersLineCollapseIntoOne() {
        List<Transcript.Entry> t = Transcript.merge("G", logs(
                List.of(owner(1000, "G", 1, "我回来了", "阿岚")),
                List.of(owner(1008, "G", 1, "我回来了", "小柚"))));

        assertEquals(1, t.size());
        assertEquals(1000, t.get(0).ts(), "留最早落盘的那份");
    }

    /** 主人真的说了两遍:发言号不同,是两句。这正是不能拿原文或时间去猜的原因。 */
    @Test
    void sayingTheSameThingTwiceStaysTwoLines() {
        List<Transcript.Entry> t = Transcript.merge("G", logs(
                List.of(owner(1000, "G", 1, "好", "阿岚"), owner(1500, "G", 2, "好", "阿岚")),
                List.of(owner(1003, "G", 1, "好", "小柚"), owner(1504, "G", 2, "好", "小柚"))));

        assertEquals(2, t.size());
    }

    /** 斜杠命令不经 say、不拨号,和上一句共一个发言号——靠原文分开。 */
    @Test
    void aCommandEchoSharingTheTurnNumberIsNotSwallowed() {
        List<Transcript.Entry> t = Transcript.merge("G", logs(
                List.of(owner(1000, "G", 3, "去挖铁", "阿岚"), owner(1200, "G", 3, "/skill mining", "阿岚")),
                List.of(owner(1002, "G", 3, "去挖铁", "小柚"))));

        assertEquals(2, t.size());
    }

    @Test
    void otherConversationsStayOut() {
        List<Transcript.Entry> t = Transcript.merge("G", logs(
                List.of(owner(1000, null, 0, "私下说的", ""), reply(1001, null, "嗯"),
                        owner(2000, "G", 1, "群里说的", "阿岚")),
                List.of(reply(2100, "H", "别的群"))));

        assertEquals(1, t.size());
        assertEquals(2000, t.get(0).ts());
    }

    /** 就他俩的会话没有发言号:同样的话说两遍就是两遍,从不合并。 */
    @Test
    void linesWithoutATurnNumberNeverMerge() {
        List<Transcript.Entry> t = Transcript.merge(null, logs(
                List.of(new ConvoLog.Line(1000, null, new ConvoState.Msg.User("<query>好</query>")),
                        new ConvoLog.Line(1100, null, new ConvoState.Msg.User("<query>好</query>"))),
                List.of()));

        assertEquals(2, t.size());
    }

    @Test
    void repliesInterleaveByTimeAndKeepTheirSpeaker() {
        List<Transcript.Entry> t = Transcript.merge("G", logs(
                List.of(owner(1000, "G", 1, "都在吗", "阿岚"), reply(1300, "G", "在")),
                List.of(owner(1004, "G", 1, "都在吗", "小柚"), reply(1200, "G", "在的"))));

        assertEquals(List.of(1000L, 1200L, 1300L), t.stream().map(Transcript.Entry::ts).toList());
        assertEquals(LAN, t.get(1).companion());
        assertEquals(YOU, t.get(2).companion());
    }

    @Test
    void theAudienceLineRoundTrips() {
        String line = Audience.line(17, "阿岚、小梅");
        assertEquals(17, Audience.turnOf("<query>x</query>\n" + line).getAsInt());
        assertTrue(Audience.turnOf("<query>x</query>").isEmpty(), "没挂 audience 就没有号");
    }
}
