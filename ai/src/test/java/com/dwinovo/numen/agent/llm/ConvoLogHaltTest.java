package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切断点落盘再读回来还是切断点。
 *
 * <p>它是历史的一部分,不是补丁:重启后模型那一侧要靠它给悬空调用补上真实原因
 * (而不是笼统的"游戏关了"),面板那一侧要靠它画出中断分隔——两个视图都得验。
 */
class ConvoLogHaltTest {

    private static final ConvoState.Msg USER = new ConvoState.Msg.User("去挖点铁");
    private static final ConvoState.Msg CALL = new ConvoState.Msg.Assistant(new AssistantTurn("",
            List.of(new LlmToolCall("c1", "goto", "{}")), null));
    private static final ConvoState.Msg HALT = new ConvoState.Msg.Halt("你死了(被僵尸杀死)");

    @Test
    void aHaltRoundTripsIntoBothViews(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(USER);
        log.append(CALL);
        log.append(HALT);

        assertEquals(List.of(USER, CALL, HALT), log.load(100), "模型视图照实回放切断点");
        assertEquals(List.of(USER, CALL, HALT), log.loadDisplay(100), "面板视图画成中断分隔");
    }

    @Test
    void aHaltIsAnEventRecordOnDisk(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(HALT);

        String last = Files.readAllLines(log.file(), StandardCharsets.UTF_8).get(1);
        assertTrue(last.startsWith("{\"type\":\"halt\",\"reason\":\"你死了(被僵尸杀死)\""),
                "按事件记(有 type、没有 role),旧版读到也只会当成不认识的事件跳过:" + last);
    }

    @Test
    void aHaltPreservedAcrossCompactionComesBack(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(USER);
        log.appendCompactSummary("[摘要] 她去挖铁", List.of(USER, CALL, HALT), new JsonObject());

        assertEquals(List.of(new ConvoState.Msg.User("[摘要] 她去挖铁"), USER, CALL, HALT), log.load(100));
    }

    @Test
    void theRestoredHistoryBecomesAValidRequestWithTheRealReason(@TempDir Path dir) {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(USER);
        log.append(CALL);
        log.append(HALT);

        List<ConvoState.Msg> wire = ProtocolView.forWire(log.load(100));

        ConvoState.Msg.Tool filled = (ConvoState.Msg.Tool) wire.get(2);
        assertTrue(filled.content().contains("你死了(被僵尸杀死)"), filled.content());
    }
}
