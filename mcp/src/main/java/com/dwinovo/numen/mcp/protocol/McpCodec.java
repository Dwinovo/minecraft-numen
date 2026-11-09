package com.dwinovo.numen.mcp.protocol;

import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MCP 的消息正文与我们的 record 之间的换算,两个方向、服务端与客户端都在这一处。哪个版本写不写哪个字段问 {@link ProtocolVersion},
 * 这里照着写;读的时候不认识的字段一律忽略。
 *
 * <p>读坏了(缺必需的字段、类型不对)抛 {@link RpcException},码是 {@link RpcError#INVALID_PARAMS}。
 */
public final class McpCodec {

    private McpCodec() {}

    // ---- 握手 ----

    /** 客户端发的 initialize 里服务端要的那几样。{@code formElicitation}:它会答表单式的 elicitation。 */
    public record InitializeParams(String protocolVersion, Implementation clientInfo, boolean formElicitation) {}

    public static JsonObject initializeParams(ProtocolVersion version, Implementation clientInfo, boolean formElicitation) {
        JsonObject capabilities = new JsonObject();
        if (formElicitation) {
            JsonObject elicitation = new JsonObject();
            elicitation.add("form", new JsonObject());
            capabilities.add("elicitation", elicitation);
        }
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", version.wire());
        params.add("capabilities", capabilities);
        params.add("clientInfo", implementation(version, clientInfo));
        return params;
    }

    public static InitializeParams initializeParams(JsonObject params) throws RpcException {
        String requested = string(params, "protocolVersion");
        if (requested == null) {
            throw invalid("initialize: protocolVersion is required");
        }
        Implementation client = params.get("clientInfo") instanceof JsonObject info ? implementation(info)
                : new Implementation("unknown client", null, "", null, List.of());
        JsonObject capabilities = params.get("capabilities") instanceof JsonObject c ? c : new JsonObject();
        return new InitializeParams(requested, client, formElicitation(capabilities, ProtocolVersion.negotiate(requested)));
    }

    /**
     * 客户端能力里会不会答表单式的 elicitation。2025-11-25 分 {@code form}/{@code url},空对象等于只会表单;2025-06-18 有这一项就是表单;
     * 2025-03-26 没有 elicitation。
     */
    private static boolean formElicitation(JsonObject capabilities, ProtocolVersion version) {
        if (!version.elicitation() || !(capabilities.get("elicitation") instanceof JsonObject e)) {
            return false;
        }
        return !version.elicitationModes() || e.has("form") || !e.has("url");
    }

    /** 服务端答 initialize:只声明工具(会发工具表变了的通知)。 */
    public static JsonObject initializeResult(ProtocolVersion version, Implementation serverInfo, String instructions) {
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", true);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", tools);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version.wire());
        result.add("capabilities", capabilities);
        result.add("serverInfo", implementation(version, serverInfo));
        if (instructions != null && !instructions.isEmpty()) {
            result.addProperty("instructions", instructions);
        }
        return result;
    }

    /** 读服务端的握手答复。它答的版本我们不说的,抛出(规范:客户端应当断开)。 */
    public static InitializeResult initializeResult(JsonElement json) throws RpcException {
        JsonObject result = object(json, "initialize result");
        String wire = string(result, "protocolVersion");
        ProtocolVersion version = ProtocolVersion.of(wire)
                .orElseThrow(() -> invalid("the server answered with protocol version " + wire + ", which this client does not speak"));
        Implementation server = result.get("serverInfo") instanceof JsonObject info ? implementation(info)
                : new Implementation("unknown server", null, "", null, List.of());
        JsonObject capabilities = result.get("capabilities") instanceof JsonObject c ? c : new JsonObject();
        boolean listChanged = capabilities.get("tools") instanceof JsonObject t && t.get("listChanged") instanceof JsonPrimitive p
                && p.isBoolean() && p.getAsBoolean();
        return new InitializeResult(version, server, listChanged, string(result, "instructions"));
    }

    private static JsonObject implementation(ProtocolVersion version, Implementation info) {
        JsonObject o = new JsonObject();
        o.addProperty("name", info.name());
        if (version.titles() && info.title() != null) {
            o.addProperty("title", info.title());
        }
        o.addProperty("version", info.version());
        if (version.icons()) {
            if (info.description() != null) {
                o.addProperty("description", info.description());
            }
            if (!info.icons().isEmpty()) {
                o.add("icons", icons(info.icons()));
            }
        }
        return o;
    }

    private static Implementation implementation(JsonObject o) {
        String name = string(o, "name");
        String version = string(o, "version");
        return new Implementation(name == null ? "unknown" : name, string(o, "title"), version == null ? "" : version,
                string(o, "description"), icons(o));
    }

    // ---- 工具表 ----

    /** {@code tools/list} 的一页。{@code nextCursor} 没有下一页是 null。 */
    public record ToolsPage(List<Tool> tools, String nextCursor) {}

    public static JsonObject listToolsParams(String cursor) {
        JsonObject params = new JsonObject();
        if (cursor != null) {
            params.addProperty("cursor", cursor);
        }
        return params;
    }

    public static JsonObject toolsPage(ProtocolVersion version, List<Tool> tools) {
        JsonArray array = new JsonArray();
        tools.forEach(tool -> array.add(tool(version, tool)));
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    public static ToolsPage toolsPage(JsonElement json) throws RpcException {
        JsonObject result = object(json, "tools/list result");
        if (!(result.get("tools") instanceof JsonArray array)) {
            throw invalid("tools/list result: tools is required");
        }
        List<Tool> tools = new ArrayList<>();
        for (JsonElement one : array) {
            tools.add(tool(object(one, "tool")));
        }
        return new ToolsPage(tools, string(result, "nextCursor"));
    }

    private static JsonObject tool(ProtocolVersion version, Tool tool) {
        JsonObject o = new JsonObject();
        o.addProperty("name", tool.name());
        if (version.titles() && tool.title() != null) {
            o.addProperty("title", tool.title());
        }
        if (tool.description() != null) {
            o.addProperty("description", tool.description());
        }
        o.add("inputSchema", tool.inputSchema());
        ToolAnnotations a = tool.annotations();
        if (a != null) {
            JsonObject annotations = new JsonObject();
            flag(annotations, "readOnlyHint", a.readOnlyHint());
            flag(annotations, "destructiveHint", a.destructiveHint());
            flag(annotations, "idempotentHint", a.idempotentHint());
            flag(annotations, "openWorldHint", a.openWorldHint());
            o.add("annotations", annotations);
        }
        if (version.icons() && !tool.icons().isEmpty()) {
            o.add("icons", icons(tool.icons()));
        }
        return o;
    }

    private static Tool tool(JsonObject o) throws RpcException {
        String name = string(o, "name");
        if (name == null) {
            throw invalid("tool: name is required");
        }
        JsonObject schema = o.get("inputSchema") instanceof JsonObject s ? s : new JsonObject();
        ToolAnnotations annotations = o.get("annotations") instanceof JsonObject a
                ? new ToolAnnotations(bool(a, "readOnlyHint"), bool(a, "destructiveHint"), bool(a, "idempotentHint"),
                bool(a, "openWorldHint"))
                : null;
        return new Tool(name, string(o, "title"), string(o, "description"), schema, annotations, icons(o));
    }

    // ---- 调用 ----

    /** 客户端发的 {@code tools/call}。{@code progressToken} 没带是 null。 */
    public record CallToolParams(String name, JsonObject arguments, JsonPrimitive progressToken) {}

    public static JsonObject callToolParams(String name, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return params;
    }

    public static CallToolParams callToolParams(JsonObject params) throws RpcException {
        String name = string(params, "name");
        if (name == null) {
            throw invalid("tools/call: name is required");
        }
        JsonElement args = params.get("arguments");
        if (args != null && !args.isJsonNull() && !args.isJsonObject()) {
            throw invalid("tools/call: arguments must be an object");
        }
        JsonPrimitive token = params.get("_meta") instanceof JsonObject meta
                && meta.get("progressToken") instanceof JsonPrimitive p && (p.isString() || p.isNumber()) ? p : null;
        return new CallToolParams(name, args instanceof JsonObject o ? o : new JsonObject(), token);
    }

    public static JsonObject callToolResult(CallToolResult result) {
        JsonArray content = new JsonArray();
        for (Content block : result.content()) {
            content.add(content(block));
        }
        JsonObject o = new JsonObject();
        o.add("content", content);
        if (result.structuredContent() != null) {
            o.add("structuredContent", result.structuredContent());
        }
        o.addProperty("isError", result.isError());
        return o;
    }

    public static CallToolResult callToolResult(JsonElement json) throws RpcException {
        JsonObject o = object(json, "tools/call result");
        List<Content> content = new ArrayList<>();
        if (o.get("content") instanceof JsonArray array) {
            for (JsonElement one : array) {
                content.add(content(object(one, "content block")));
            }
        }
        JsonElement structured = o.get("structuredContent");
        return new CallToolResult(content, structured == null || structured.isJsonNull() ? null : structured,
                Boolean.TRUE.equals(bool(o, "isError")));
    }

    private static JsonObject content(Content block) {
        JsonObject o = new JsonObject();
        // Java 17 没有 switch 的类型模式:按密封的五种逐个判断,剩下的只能是内嵌资源
        if (block instanceof Content.Text t) {
            o.addProperty("type", "text");
            o.addProperty("text", t.text());
        } else if (block instanceof Content.Image i) {
            o.addProperty("type", "image");
            o.addProperty("data", i.data());
            o.addProperty("mimeType", i.mimeType());
        } else if (block instanceof Content.Audio a) {
            o.addProperty("type", "audio");
            o.addProperty("data", a.data());
            o.addProperty("mimeType", a.mimeType());
        } else if (block instanceof Content.ResourceLink l) {
            o.addProperty("type", "resource_link");
            o.addProperty("uri", l.uri());
            o.addProperty("name", l.name());
            if (l.mimeType() != null) {
                o.addProperty("mimeType", l.mimeType());
            }
        } else {
            Content.Resource r = (Content.Resource) block;
            JsonObject resource = new JsonObject();
            resource.addProperty("uri", r.uri());
            if (r.mimeType() != null) {
                resource.addProperty("mimeType", r.mimeType());
            }
            if (r.text() != null) {
                resource.addProperty("text", r.text());
            } else {
                resource.addProperty("blob", r.blob());
            }
            o.addProperty("type", "resource");
            o.add("resource", resource);
        }
        return o;
    }

    private static Content content(JsonObject o) throws RpcException {
        String type = string(o, "type");
        return switch (type == null ? "" : type) {
            case "text" -> new Content.Text(orEmpty(string(o, "text")));
            case "image" -> new Content.Image(orEmpty(string(o, "data")), string(o, "mimeType"));
            case "audio" -> new Content.Audio(orEmpty(string(o, "data")), string(o, "mimeType"));
            case "resource_link" -> new Content.ResourceLink(orEmpty(string(o, "uri")), string(o, "name"), string(o, "mimeType"));
            case "resource" -> {
                JsonObject r = object(o.get("resource"), "embedded resource");
                yield new Content.Resource(orEmpty(string(r, "uri")), string(r, "mimeType"), string(r, "text"), string(r, "blob"));
            }
            default -> throw invalid("unknown content type: " + type);
        };
    }

    /** 进度通知:{@code progress} 每条都要比上一条大;总量不知道就不写。 */
    public static JsonObject progress(JsonPrimitive token, long progress, String message) {
        JsonObject params = new JsonObject();
        params.add("progressToken", token);
        params.addProperty("progress", progress);
        if (message != null) {
            params.addProperty("message", message);
        }
        return params;
    }

    // ---- elicitation ----

    public static JsonObject elicitRequest(ProtocolVersion version, ElicitRequest request) {
        JsonObject params = new JsonObject();
        if (version.elicitationModes()) {
            params.addProperty("mode", "form");
        }
        params.addProperty("message", request.message());
        params.add("requestedSchema", request.requestedSchema());
        return params;
    }

    /** 客户端读服务端发来的表单。网址模式的不是表单,抛出。 */
    public static ElicitRequest elicitRequest(JsonObject params) throws RpcException {
        String mode = string(params, "mode");
        if (mode != null && !mode.equals("form")) {
            throw invalid("elicitation mode " + mode + " is not supported");
        }
        String message = string(params, "message");
        return new ElicitRequest(message == null ? "" : message,
                params.get("requestedSchema") instanceof JsonObject s ? s : new JsonObject());
    }

    public static JsonObject elicitResult(ElicitResult result) {
        JsonObject o = new JsonObject();
        o.addProperty("action", result.action().name().toLowerCase(Locale.ROOT));
        if (result.content() != null) {
            o.add("content", result.content());
        }
        return o;
    }

    public static ElicitResult elicitResult(JsonElement json) throws RpcException {
        JsonObject o = object(json, "elicitation result");
        String action = string(o, "action");
        ElicitResult.Action parsed = switch (action == null ? "" : action) {
            case "accept" -> ElicitResult.Action.ACCEPT;
            case "decline" -> ElicitResult.Action.DECLINE;
            case "cancel" -> ElicitResult.Action.CANCEL;
            default -> throw invalid("elicitation result: unknown action " + action);
        };
        return new ElicitResult(parsed, o.get("content") instanceof JsonObject c ? c : null);
    }

    // ---- 零件 ----

    private static JsonArray icons(List<Icon> icons) {
        JsonArray array = new JsonArray();
        for (Icon icon : icons) {
            JsonObject o = new JsonObject();
            o.addProperty("src", icon.src());
            if (icon.mimeType() != null) {
                o.addProperty("mimeType", icon.mimeType());
            }
            if (!icon.sizes().isEmpty()) {
                JsonArray sizes = new JsonArray();
                icon.sizes().forEach(sizes::add);
                o.add("sizes", sizes);
            }
            array.add(o);
        }
        return array;
    }

    private static List<Icon> icons(JsonObject owner) {
        List<Icon> icons = new ArrayList<>();
        if (owner.get("icons") instanceof JsonArray array) {
            for (JsonElement one : array) {
                if (one instanceof JsonObject o && string(o, "src") != null) {
                    List<String> sizes = new ArrayList<>();
                    if (o.get("sizes") instanceof JsonArray s) {
                        s.forEach(size -> sizes.add(size.getAsString()));
                    }
                    icons.add(new Icon(string(o, "src"), string(o, "mimeType"), sizes));
                }
            }
        }
        return icons;
    }

    private static void flag(JsonObject o, String key, Boolean value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static JsonObject object(JsonElement json, String what) throws RpcException {
        if (json instanceof JsonObject o) {
            return o;
        }
        throw invalid(what + " must be an object");
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static Boolean bool(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static RpcException invalid(String message) {
        return new RpcException(RpcError.INVALID_PARAMS, message);
    }
}
