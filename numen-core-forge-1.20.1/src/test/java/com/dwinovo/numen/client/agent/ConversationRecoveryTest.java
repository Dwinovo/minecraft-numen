package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationRecoveryTest {
    @Test void extractsTaggedUnclosedAndFallbackSummaries() {
        assertEquals("kept", ConversationRecovery.extractSummary("<analysis>drop</analysis><summary> kept </summary>"));
        assertEquals("kept", ConversationRecovery.extractSummary("<summary>kept"));
        assertEquals("kept", ConversationRecovery.extractSummary("<analysis>drop</analysis> kept"));
    }

    @Test void tokenEstimateChargesCjkMoreThanAscii() {
        int ascii = ConversationRecovery.estimateContextTokens(List.of(new ConvoState.Msg.User("abcdefgh")));
        int cjk = ConversationRecovery.estimateContextTokens(List.of(new ConvoState.Msg.User("测试测试测试测试")));
        assertTrue(cjk > ascii);
    }

    @Test void escapesXmlAndUnwrapsRootCause() {
        assertEquals("&lt;a&amp;b&gt;", ConversationRecovery.escapeXml("<a&b>"));
        assertEquals("IllegalStateException: root", ConversationRecovery.rootMessage(
                new RuntimeException("outer", new IllegalStateException("root"))));
    }
}
