package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.CompanionHome;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.command.ChatCommands;
import com.dwinovo.numen.client.ui.KeyCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入行上斜杠命令能不能用,只看会话有没有单一的主(宿主的 {@code loop()})。
 * 没有时(群会话)斜杠输入不当话发给全体,回一句 {@link ChatCommands#SOLO_ONLY};
 * 有时(私聊)照旧交给命令层。
 */
class ChatInputBarCommandTest {

    private static final UUID HER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @TempDir
    Path home;

    @BeforeEach
    void useTempHome() {
        CompanionHome.init(home);
    }

    @AfterEach
    void restore() {
        CompanionHome.init(null);
    }

    /** 记下输入行交给宿主的两样东西:说出去的话、命令的回话。 */
    private static final class Host implements ChatInputBar.Host {
        final EntityAgentLoop loop;
        final List<String> sent = new ArrayList<>();
        final List<String> replies = new ArrayList<>();

        Host(EntityAgentLoop loop) {
            this.loop = loop;
        }

        @Override public void onSend(String text) { sent.add(text); }
        @Override public void onAbort() { }
        @Override public boolean canAbort() { return false; }
        @Override public String hint() { return ""; }
        @Override public EntityAgentLoop loop() { return loop; }
        @Override public Conversation conversation() { return null; }
        @Override public void onCommandReply(String reply) { replies.add(reply); }
    }

    private static ChatInputBar bar(Host host) {
        ChatInputBar bar = new ChatInputBar(host, EnumSet.of(ChatInputBar.Key.SEND));
        bar.build(0, 0, 200, 20, 0);
        return bar;
    }

    private static void type(ChatInputBar bar, String text) {
        bar.setText(text);
        bar.keyPressed(KeyCodes.ENTER, 0);
    }

    @Test
    void inAConversationWithoutASingleCompanionClearIsNotSentToEveryone() {
        Host host = new Host(null);
        ChatInputBar bar = bar(host);
        type(bar, "/clear");
        assertTrue(host.sent.isEmpty(), "群里的 /clear 不该当成一句话发给全体:" + host.sent);
        assertEquals(List.of(ChatCommands.SOLO_ONLY), host.replies);
        assertEquals("", bar.text(), "和别的命令回话一样,用过的那串清掉");
    }

    @Test
    void aBareSlashGetsTheSameSentenceNotTheCommandList() {
        Host host = new Host(null);
        type(bar(host), "/");
        assertTrue(host.sent.isEmpty(), host.sent.toString());
        assertEquals(List.of(ChatCommands.SOLO_ONLY), host.replies);
    }

    @Test
    void plainWordsInThatConversationStillGoOut() {
        Host host = new Host(null);
        type(bar(host), "大家好");
        assertEquals(List.of("大家好"), host.sent);
        assertTrue(host.replies.isEmpty(), host.replies.toString());
    }

    @Test
    void facingOneCompanionTheCommandLayerAnswersAsBefore() {
        Host host = new Host(AgentLoopRegistry.getOrCreate(HER));
        type(bar(host), "/nosuchthing");
        assertTrue(host.sent.isEmpty(), host.sent.toString());
        assertEquals(1, host.replies.size());
        String reply = host.replies.get(0);
        assertNotNull(reply);
        assertNotEquals(ChatCommands.SOLO_ONLY, reply, "私聊里命令照旧交给命令层");
        assertTrue(reply.contains("/nosuchthing"), reply);
    }
}
