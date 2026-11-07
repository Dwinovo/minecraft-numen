package com.dwinovo.numen.api.client.voice;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VoiceErrorTest {

    @Test
    void fromErrorFindsOurErrorInsideWrappers() {
        VoiceError inner = VoiceError.http("Fish Audio", 402, "no credit");
        assertSame(inner, VoiceError.fromError(new CompletionException(new RuntimeException(inner))));
    }

    @Test
    void anythingElseBecomesUnknownWithTypeAndMessage() {
        VoiceError e = VoiceError.fromError(new CompletionException(new java.net.ConnectException("refused")));
        assertEquals(VoiceError.UNKNOWN, e.code());
        assertEquals("ConnectException", e.arg(0));
        assertEquals("refused", e.arg(1));
    }

    @Test
    void anHttpErrorCarriesTheVendorsOwnWordsNotTheWholeBody() {
        VoiceError e = VoiceHttp.httpError("Fish Audio", 402, "{\"message\":\"Insufficient API credit\",\"x\":1}");
        assertEquals(VoiceError.HTTP, e.code());
        assertEquals("Fish Audio", e.arg(0));
        assertEquals("402", e.arg(1));
        assertEquals("Insufficient API credit", e.arg(2));
    }

    @Test
    void theLogMessageHasTheCodeAndTheWords() {
        String m = VoiceError.of(VoiceError.API, "MiniMax", "1004: invalid api key").getMessage();
        assertEquals(true, m.contains("api") && m.contains("1004: invalid api key"), m);
    }
}
