package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.permission.Verb;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 点击与悬停事件的类要 MC 的注册表起来才能用。 */
@Tag("mc")
class ConsentChatTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static ConsentRequestPayload.Line line(String name, int count) {
        return new ConsentRequestPayload.Line(Verb.BREAK, null, Component.literal(name), count,
                Component.literal("placed by a player"), false);
    }

    private static ConsentRequestPayload request(long id, ConsentRequestPayload.Line... lines) {
        return new ConsentRequestPayload(UUID.randomUUID(), id, List.of(lines), List.of("allow break(oak_log)"),
                List.of(), List.of(), 0L, null);
    }

    private static List<String> commands(Component message) {
        List<String> out = new ArrayList<>();
        if (message.getStyle().getClickEvent() instanceof ClickEvent click) {
            out.add(click.getValue());
        }
        message.getSiblings().forEach(sibling -> out.addAll(commands(sibling)));
        return out;
    }

    private static boolean says(Component message, String key) {
        return message.getContents() instanceof TranslatableContents t && t.getKey().equals(key)
                || message.getSiblings().stream().anyMatch(sibling -> says(sibling, key))
                || message.getContents() instanceof TranslatableContents u
                && java.util.Arrays.stream(u.getArgs()).anyMatch(arg -> arg instanceof Component c && says(c, key));
    }

    @Test
    void theButtonsAreTheThreeAnswersOfTheConsentCommandForThisRequest() {
        Component message = ConsentChat.ask("小柚", request(42, line("oak log", 6)));
        assertEquals(List.of("/numen consent allow 42", "/numen consent remember 42", "/numen consent deny 42"),
                commands(message));
        assertTrue(says(message, Keys.CONSENT_ASK) && says(message, Keys.CONSENT_VERB_PREFIX + "break"));
        assertTrue(message.getString().startsWith("[小柚] "));
    }

    @Test
    void severalThingsAreAskedTogetherAndTheOverflowIsCounted() {
        Component message = ConsentChat.ask("小柚", request(7, line("a", 1), line("b", 1), line("c", 1), line("d", 1),
                line("e", 1)));
        assertTrue(says(message, Keys.CONSENT_ASK_MANY));
        assertTrue(message.getString().contains("+2"), message.getString());
        assertEquals(3, commands(message).size());
    }

    @Test
    void aWithdrawalSaysWhoseRequestWentAwayAndWhy() {
        Component message = ConsentChat.withdrawn("小柚", ConsentDesk.Withdrawal.TASK_ENDED);
        TranslatableContents contents = (TranslatableContents) message.getContents();
        assertEquals(Keys.CONSENT_WITHDRAWN, contents.getKey());
        assertEquals("小柚", contents.getArgs()[0]);
        assertTrue(says((Component) contents.getArgs()[1], ConsentDesk.Withdrawal.TASK_ENDED.key()));
    }
}
