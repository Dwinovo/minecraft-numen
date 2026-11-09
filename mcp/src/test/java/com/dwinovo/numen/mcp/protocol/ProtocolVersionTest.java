package com.dwinovo.numen.mcp.protocol;

import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 版本协商与各版本线格式的差异(逐条对过 schema.ts):三个版本各一条,以及不认识的版本。 */
class ProtocolVersionTest {

    private static final Implementation SERVER = new Implementation("s", "Server", "1", "a server",
            List.of(new Icon("data:image/png;base64,AA", "image/png", List.of("1x1"))));

    @Test
    void version20251125IsAnsweredInKindWithIconsDescriptionAndElicitationModes() throws Exception {
        ProtocolVersion v = ProtocolVersion.negotiate("2025-11-25");
        assertEquals(ProtocolVersion.V2025_11_25, v);
        JsonObject info = McpCodec.initializeResult(v, SERVER, "hi").getAsJsonObject("serverInfo");
        assertTrue(info.has("title") && info.has("description") && info.has("icons"));
        assertTrue(v.titledEnumsAsOneOf() && v.elicitationModes() && !v.batching());
        assertEquals("form", McpCodec.elicitRequest(v, new ElicitRequest("m", new JsonObject())).get("mode").getAsString());
        JsonObject caps = JsonParser.parseString("{\"elicitation\":{\"url\":{}}}").getAsJsonObject();
        assertFalse(McpCodec.initializeParams(init("2025-11-25", caps)).formElicitation(), "只会网址模式的不问表单");
        assertTrue(McpCodec.initializeParams(init("2025-11-25", JsonParser.parseString("{\"elicitation\":{}}")
                .getAsJsonObject())).formElicitation(), "空对象等于只会表单");
    }

    @Test
    void version20250618HasTitlesAndFormElicitationButNoIconsOrModes() throws Exception {
        ProtocolVersion v = ProtocolVersion.negotiate("2025-06-18");
        assertEquals(ProtocolVersion.V2025_06_18, v);
        JsonObject info = McpCodec.initializeResult(v, SERVER, null).getAsJsonObject("serverInfo");
        assertTrue(info.has("title") && !info.has("description") && !info.has("icons"));
        assertTrue(v.elicitation() && !v.elicitationModes() && !v.titledEnumsAsOneOf() && !v.batching());
        assertFalse(McpCodec.elicitRequest(v, new ElicitRequest("m", new JsonObject())).has("mode"));
        assertTrue(McpCodec.initializeParams(init("2025-06-18", JsonParser.parseString("{\"elicitation\":{}}")
                .getAsJsonObject())).formElicitation());
    }

    @Test
    void version20250326HasBatchesAndNeitherTitlesNorElicitation() throws Exception {
        ProtocolVersion v = ProtocolVersion.negotiate("2025-03-26");
        assertEquals(ProtocolVersion.V2025_03_26, v);
        JsonObject info = McpCodec.initializeResult(v, SERVER, null).getAsJsonObject("serverInfo");
        assertTrue(!info.has("title") && !info.has("icons"));
        assertTrue(v.batching() && !v.elicitation());
        assertFalse(McpCodec.initializeParams(init("2025-03-26", JsonParser.parseString("{\"elicitation\":{}}")
                .getAsJsonObject())).formElicitation(), "2025-03-26 没有 elicitation");
        Tool tool = new Tool("t", "T", "d", new JsonObject(), null, SERVER.icons());
        JsonObject listed = McpCodec.toolsPage(v, List.of(tool)).getAsJsonArray("tools").get(0).getAsJsonObject();
        assertTrue(!listed.has("title") && !listed.has("icons"), listed.toString());
    }

    @Test
    void anUnknownVersionGetsTheLatestAndAClientRefusesAServerVersionItDoesNotSpeak() {
        assertEquals(ProtocolVersion.LATEST, ProtocolVersion.negotiate("2024-11-05"));
        assertEquals(ProtocolVersion.LATEST, ProtocolVersion.negotiate("nonsense"));
        JsonObject result = JsonParser.parseString("{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                + "\"serverInfo\":{\"name\":\"old\",\"version\":\"1\"}}").getAsJsonObject();
        RpcException refused = assertThrows(RpcException.class, () -> McpCodec.initializeResult(result));
        assertTrue(refused.getMessage().contains("2024-11-05"));
    }

    private static JsonObject init(String version, JsonObject capabilities) {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", version);
        params.add("capabilities", capabilities);
        return params;
    }
}
