package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DashScopeTtsTest {

    @Test
    void defaultsToTheRealtimeTtsModel() {
        assertEquals("qwen3-tts-flash-realtime", DashScopeTts.DEFAULT_MODEL);
    }

    @Test
    void buildsServerCommitTtsMessages() {
        List<String> messages = DashScopeTts.buildRequestMessages("hello", "Cherry");
        assertEquals(3, messages.size());

        JsonObject update = JsonParser.parseString(messages.get(0)).getAsJsonObject();
        assertEquals("session.update", update.get("type").getAsString());
        JsonObject session = update.getAsJsonObject("session");
        assertEquals("server_commit", session.get("mode").getAsString());
        assertEquals("Cherry", session.get("voice").getAsString());
        assertEquals("pcm", session.get("response_format").getAsString());
        assertEquals(24_000, session.get("sample_rate").getAsInt());
        assertFalse(session.has("modalities"));

        JsonObject append = JsonParser.parseString(messages.get(1)).getAsJsonObject();
        assertEquals("input_text_buffer.append", append.get("type").getAsString());
        assertEquals("hello", append.get("text").getAsString());

        JsonObject commit = JsonParser.parseString(messages.get(2)).getAsJsonObject();
        assertEquals("input_text_buffer.commit", commit.get("type").getAsString());
    }

    @Test
    void rejectsEmptyOrOddPcmAndEncodesModelInWebSocketUri() {
        assertThrows(IllegalArgumentException.class, () -> DashScopeTts.toWav(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> DashScopeTts.toWav(new byte[]{1}));
        assertEquals("qwen%2Ftts%20model", DashScopeTts.webSocketUri("qwen/tts model")
                .getRawQuery().substring("model=".length()));
    }
}
