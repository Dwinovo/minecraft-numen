package com.dwinovo.numen.client.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令层的解析与补全。这几条都不碰同伴({@code loop} 传 null),因为它们本来就不该碰——
 * 认不认识一条命令、怎么拆参数,跟她在干什么无关。
 */
class ChatCommandsTest {

    @Test
    void onlyASlashMakesItACommand() {
        assertTrue(ChatCommands.isCommand("/skills"));
        assertTrue(ChatCommands.isCommand("   /skills  "), "两边的空白不该影响判断");
        assertFalse(ChatCommands.isCommand("skills"));
        assertFalse(ChatCommands.isCommand("你好 /skills"), "斜杠得在开头");
        assertTrue(ChatCommands.isCommand("/"),
                "光一个斜杠也算 —— 主人打了斜杠就是要用命令,该给他看清单,"
                        + "而不是把裸斜杠当聊天发给她");
        assertFalse(ChatCommands.isCommand(null));
    }

    @Test
    void parseSplitsTheNameFromTheRest() {
        ChatCommands.Parsed p = ChatCommands.parse("/build 在河边盖个木屋");
        assertEquals("build", p.name());
        assertEquals("在河边盖个木屋", p.args());
    }

    @Test
    void aBareCommandHasEmptyArgsNotNull() {
        assertEquals("", ChatCommands.parse("/skills").args());
        assertEquals("", ChatCommands.parse("/skills   ").args());
    }

    @Test
    void argumentsKeepTheirOwnInnerSpacing() {
        assertEquals("盖 一间  木屋", ChatCommands.parse("/build   盖 一间  木屋 ").args());
    }

    @Test
    void unknownCommandIsRefusedRatherThanSentAsChat() {
        String reply = ChatCommands.dispatch(null, "/nosuchthing");
        assertNotNull(reply);
        assertTrue(reply.contains("/nosuchthing"), reply);
        // 认不出就报错。兜底成"当自然语言发出去"的话,打错的命令会变成一句话发给模型,
        // 而主人以为自己下了个命令 —— 然后就得反过来手写一套拼写猜测去救它。
    }

    @Test
    void theBuiltinListingShowsUpWhenYouTypeJustASlash() {
        List<Completion> rows = ChatCommands.complete(null, "/");
        assertTrue(rows.stream().anyMatch(r -> r.label().equals("/skills")), rows.toString());
    }

    @Test
    void completionFiltersByPrefixAndIsCaseBlind() {
        assertFalse(ChatCommands.complete(null, "/SKI").isEmpty());
        assertTrue(ChatCommands.complete(null, "/zzz").isEmpty());
    }

    @Test
    void aTrailingSpaceMeansTheNameIsDoneAndArgsBegin() {
        // "/skills" 还在打名字 → 列命令;"/skills " 名字打完了 → 交给这条命令补参数
        // (它不吃参数,所以是空)。右边那个空格是唯一的信号,不能被 strip 掉。
        assertFalse(ChatCommands.complete(null, "/skills").isEmpty());
        assertTrue(ChatCommands.complete(null, "/skills ").isEmpty());
    }

    @Test
    void completionForACommandWithoutArgumentsDoesNotAppendASpace() {
        Completion skills = ChatCommands.complete(null, "/skills").get(0);
        assertEquals("/skills", skills.insert(), "不吃参数的命令补完就该能直接回车");
    }

    @Test
    void nonCommandTextHasNoCompletions() {
        assertTrue(ChatCommands.complete(null, "你好").isEmpty());
        assertTrue(ChatCommands.complete(null, "").isEmpty());
    }
}
