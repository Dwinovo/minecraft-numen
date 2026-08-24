package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 会话日志读回来的东西同样受 {@link LlmToolCall} 的契约管。
 *
 * <p>这条单独测,是因为磁盘是<b>唯一一条能绕过构造现场的入口</b>:早先落盘的那些行是在契约
 * 立起来之前写的,里面什么都有可能。所以这里故意手写带毒的 JSONL 灌进去——用构造好的对象
 * 去测等于自己考自己,构造器早把它净化了,那样测不出任何东西。
 */
class ConvoLogPoisonTest {

    /** 一条早先落盘的、参数被截断的 assistant 记录。 */
    private static final String POISONED = """
            {"role":"assistant","content":"我这就去","tool_calls":[\
            {"id":"c1","name":"goto","arguments":"{\\"x\\": "}]}""";

    private static ConvoLog logWith(Path dir, String... lines) throws IOException {
        Path file = dir.resolve("convo.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return ConvoLog.atFile(file);
    }

    @Test
    void argumentsWrittenBeforeTheContractExistedComeBackClean(@TempDir Path dir) throws IOException {
        List<ConvoState.Msg> history = logWith(dir, POISONED).load(100);

        assertEquals(1, history.size());
        var assistant = assertInstanceOf(ConvoState.Msg.Assistant.class, history.get(0));
        LlmToolCall tc = assistant.turn().toolCalls().get(0);

        assertEquals(LlmToolCall.NO_ARGS, tc.arguments());
        assertDoesNotThrow(() -> JsonParser.parseString(tc.arguments()));
        assertEquals("c1", tc.id(), "id 得原样留着,tool_call_id 要靠它配对");
        assertEquals("我这就去", assistant.turn().content(), "正文不受影响");
    }

    @Test
    void aPoisonedLineDoesNotTakeTheRestOfTheHistoryWithIt(@TempDir Path dir) throws IOException {
        List<ConvoState.Msg> history = logWith(dir,
                "{\"role\":\"user\",\"content\":\"去挖点铁\"}",
                POISONED,
                "{\"role\":\"user\",\"content\":\"还在吗\"}").load(100);

        assertEquals(3, history.size(), "带毒那行要被治好,不是被丢掉——丢了对话就断了");
    }

    @Test
    void aRewrittenLogNoLongerContainsTheBrokenText(@TempDir Path dir) throws IOException {
        // 读回来是干净的,再写出去自然也干净:毒药不会在磁盘上自我延续
        ConvoLog source = logWith(dir, POISONED);
        Path copy = dir.resolve("copy.jsonl");
        ConvoLog target = ConvoLog.atFile(copy);
        source.load(100).forEach(target::append);

        String written = Files.readString(copy, StandardCharsets.UTF_8);
        assertFalse(written.contains("{\\\"x\\\": "), "写出去的还带着半截 JSON:\n" + written);
        // JSONL:一行一条记录,逐行解
        for (String line : Files.readAllLines(copy, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                assertDoesNotThrow(() -> JsonParser.parseString(line), "这行解不开:\n" + line);
            }
        }
    }
}
