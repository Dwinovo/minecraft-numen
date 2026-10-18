package com.dwinovo.numen.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 一个 MCP 客户端:进程内 HTTP,带令牌接入、带会话号调用。用例里扮外部大脑(或渠道)。 */
final class McpTestClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final int port;
    private final String secret;
    private String session;

    McpTestClient(int port, String secret) {
        this.port = port;
        this.secret = secret;
    }

    String session() {
        return session;
    }

    private HttpRequest.Builder request(String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Authorization", "Bearer " + secret)
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        return builder;
    }

    HttpResponse<String> post(JsonObject body) throws Exception {
        return HTTP.send(request("application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static JsonObject rpc(String method, JsonObject params) {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", 1);
        req.addProperty("method", method);
        req.add("params", params);
        return req;
    }

    /** initialize:拿会话号;{@code elicitation} 声明自己会答 elicitation。 */
    HttpResponse<String> connect(boolean elicitation) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2025-06-18");
        JsonObject caps = new JsonObject();
        if (elicitation) {
            caps.add("elicitation", new JsonObject());
        }
        params.add("capabilities", caps);
        HttpResponse<String> response = post(rpc("initialize", params));
        session = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        return response;
    }

    void connect() throws Exception {
        HttpResponse<String> response = connect(false);
        if (session == null) {
            throw new IllegalStateException("no session: " + response.statusCode() + " " + response.body());
        }
    }

    /** 一次 tools/call,返回 result 对象。 */
    JsonObject callResult(String tool, JsonObject args) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args);
        return JsonParser.parseString(post(rpc("tools/call", params)).body()).getAsJsonObject()
                .getAsJsonObject("result");
    }

    /** 一次 tools/call,返回回执的第一段文本。 */
    String call(String tool, JsonObject args) throws Exception {
        return callResult(tool, args).getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** 结束会话。 */
    void disconnect() throws Exception {
        HTTP.send(request("application/json").DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    /** 开服务端→客户端的流;调用方读它。 */
    HttpResponse<java.io.InputStream> openStream() throws Exception {
        return HTTP.send(request("text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}
