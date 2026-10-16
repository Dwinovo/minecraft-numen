package com.dwinovo.numen.api.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpReceiptTest {

    @Test
    void eventsRideAfterTheReceiptText() {
        JsonObject r = McpServer.receipt("done", false, "<query>来一下</query>");

        JsonArray content = r.getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("done", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("<query>来一下</query>", content.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals(false, r.get("isError").getAsBoolean());
    }

    @Test
    void noEventsLeavesTheReceiptAlone() {
        JsonObject r = McpServer.receipt("boom", true, null);

        assertEquals(1, r.getAsJsonArray("content").size());
        assertTrue(r.get("isError").getAsBoolean());
    }
}
