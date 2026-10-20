package com.dwinovo.numen.api.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpReceiptTest {

    @Test
    void everyPieceOfTheBodyIsATextBlockInOrder() {
        JsonObject r = McpServer.receipt(new ExternalBrain.Body(
                List.of("done", "<query>来一下</query>", "<runtime_state/>"), false));

        JsonArray content = r.getAsJsonArray("content");
        assertEquals(3, content.size());
        assertEquals("done", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("<query>来一下</query>", content.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("<runtime_state/>", content.get(2).getAsJsonObject().get("text").getAsString());
        assertEquals(false, r.get("isError").getAsBoolean());
    }

    @Test
    void aFailedBodyIsAnError() {
        JsonObject r = McpServer.receipt(new ExternalBrain.Body(List.of("boom"), true));

        assertEquals(1, r.getAsJsonArray("content").size());
        assertTrue(r.get("isError").getAsBoolean());
    }
}
