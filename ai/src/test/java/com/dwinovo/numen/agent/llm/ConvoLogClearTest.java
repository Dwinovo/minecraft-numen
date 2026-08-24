package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code clear} 边界事件的两个承诺:对模型是真空白,对磁盘一个字不删。
 *
 * <p>两个视图分开验——它们对同一条事件的处理是相反的({@code load} 清空重放,
 * {@code loadDisplay} 画分隔线),只测一边等于没测。
 */
class ConvoLogClearTest {

    private static ConvoLog logWith(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("chat.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return ConvoLog.atFile(file);
    }

    private static final String USER_OLD = "{\"role\":\"user\",\"content\":\"去挖点铁\"}";
    private static final String ASSISTANT_OLD = "{\"role\":\"assistant\",\"content\":\"这就去\"}";
    private static final String CLEAR = "{\"type\":\"clear\",\"ts\":1}";
    private static final String USER_NEW = "{\"role\":\"user\",\"content\":\"我们重新认识一下\"}";

    @Test
    void llmViewRestartsFromNothingAtTheBoundary(@TempDir Path dir) throws IOException {
        List<ConvoState.Msg> history =
                logWith(dir, USER_OLD, ASSISTANT_OLD, CLEAR, USER_NEW).load(100);

        assertEquals(1, history.size(), "边界之前的一切不进模型上下文");
        var user = assertInstanceOf(ConvoState.Msg.User.class, history.get(0));
        assertEquals("我们重新认识一下", user.content());
    }

    @Test
    void displayViewKeepsEverythingAndDrawsADivider(@TempDir Path dir) throws IOException {
        List<ConvoState.Msg> shown =
                logWith(dir, USER_OLD, ASSISTANT_OLD, CLEAR, USER_NEW).loadDisplay(100);

        assertEquals(4, shown.size(), "清空不动记录,只多一条分隔线");
        var divider = assertInstanceOf(ConvoState.Msg.User.class, shown.get(2));
        assertEquals(ConvoLog.CLEAR_DIVIDER, divider.content());
    }

    @Test
    void appendedBoundaryRoundTripsAndDeletesNothing(@TempDir Path dir) throws IOException {
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        log.append(new ConvoState.Msg.User("去挖点铁"));
        log.appendClearBoundary();
        log.append(new ConvoState.Msg.User("新的开始"));

        assertEquals(1, log.load(100).size());
        assertEquals(3, log.loadDisplay(100).size());
        // 落盘层面的承诺:边界前那行原文还在文件里
        assertTrue(Files.readString(log.file(), StandardCharsets.UTF_8).contains("去挖点铁"),
                "append-only:清空不重写、不截断文件");
    }

    @Test
    void clearAfterCompactWipesTheSummaryToo(@TempDir Path dir) throws IOException {
        List<ConvoState.Msg> history = logWith(dir,
                USER_OLD,
                "{\"type\":\"compact\",\"content\":\"[摘要] 她挖过铁\"}",
                CLEAR,
                USER_NEW).load(100);

        assertEquals(1, history.size(), "摘要也是边界之前的东西,一并不带");
        assertEquals("我们重新认识一下",
                assertInstanceOf(ConvoState.Msg.User.class, history.get(0)).content());
    }
}
