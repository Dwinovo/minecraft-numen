package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 面板上的对话记录只有一份换法:这一局里边写边看到的,和下次进游戏读盘看到的,一条不差。
 *
 * <p>分隔线(整理、清空、换人设)和切断点曾经各写两遍——一遍进日志、一遍手工塞进显示列表,
 * 两边的规则迟早对不上。现在显示记录只从日志写下的东西换出来。
 */
class ConvoLogDisplayTest {

    private static final ConvoState.Msg USER = new ConvoState.Msg.User("去挖点铁");
    private static final ConvoState.Msg CALL = new ConvoState.Msg.Assistant(new AssistantTurn("",
            List.of(new LlmToolCall("c1", "goto", "{}")), null));
    private static final ConvoState.Msg RESULT = new ConvoState.Msg.Tool("c1", "{\"success\":true}");
    private static final ConvoState.Msg HALT = new ConvoState.Msg.Halt("主人按了停止");
    private static final ConvoState.Msg REPLY = new ConvoState.Msg.Assistant(new AssistantTurn("挖完了", List.of(), null));

    @Test
    void whatIsShownLiveIsWhatIsReadBackFromDisk(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        List<ConvoState.Msg> live = new ArrayList<>();
        log.onDisplay(live::add);

        log.append(USER);
        log.append(CALL);
        log.append(RESULT);
        log.append(HALT);
        log.appendCompactSummary("[摘要] 挖过铁", List.of(), null);
        log.append(REPLY);
        log.appendPersonaDivider();
        log.appendClearBoundary();

        assertEquals(log.loadDisplay(100), live, "边写边看的与读盘读回来的一条不差");
        assertEquals(List.of(USER, CALL, RESULT, HALT,
                new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER), REPLY,
                new ConvoState.Msg.User(ConvoLog.PERSONA_DIVIDER),
                new ConvoState.Msg.User(ConvoLog.CLEAR_DIVIDER)), live);
    }

    @Test
    void thinkingSurvivesTheRoundTripIntoBothViews(@TempDir Path dir) {
        // 面板要画思考块;Anthropic 回传思考块时连签名一起要它原文——重启后少了它,两边都会坏
        ConvoState.Msg thought = new ConvoState.Msg.Assistant(new AssistantTurn("先去矿洞", List.of(), null,
                "背包里没镐,得先合成一把"));
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(USER);
        log.append(thought);

        assertEquals(List.of(USER, thought), log.load(100), "模型视图");
        assertEquals(List.of(USER, thought), log.loadDisplay(100), "面板视图");
    }

    @Test
    void aFailedWriteIsStillShownForThisSession(@TempDir Path dir) {
        // 日志路径是个目录:写不进去。这一局照样得看得见刚发生的事,只是下次读盘读不回来。
        ConvoLog log = ConvoLog.atFile(dir);
        List<ConvoState.Msg> live = new ArrayList<>();
        log.onDisplay(live::add);

        log.append(USER);

        assertEquals(List.of(USER), live);
    }
}
