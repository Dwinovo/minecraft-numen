package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Chat Completions 请求里的显式缓存断点,给转发到 Anthropic 型号的网关用(见 {@link ChatCompat#anthropicCacheControl}):
 * 这些网关不会自动缓存,和 Anthropic 本家一样要在请求里标出来。断点放哪见 {@link CacheBreakpoints}。
 * 传入的数组和消息对象属于调用方,这里只返回改过的副本。
 */
final class ChatCacheControl {

    private ChatCacheControl() {}

    /** system 消息与消息尾部各放断点(没有文字内容的消息,如只带工具调用的助手消息,跳过)。 */
    static JsonArray markMessages(JsonArray messages) {
        JsonArray out = new JsonArray();
        for (JsonElement m : messages) {
            out.add(m.deepCopy());
        }
        for (int i = 0; i < out.size(); i++) {
            String role = role(out.get(i));
            if (role.equals("system") || role.equals("developer")) {
                markText(out.get(i).getAsJsonObject());
                break;
            }
        }
        int left = CacheBreakpoints.HISTORY;
        for (int i = out.size() - 1; i >= 0 && left > 0; i--) {
            String role = role(out.get(i));
            if (!role.equals("user") && !role.equals("assistant") && !role.equals("tool")) {
                continue;
            }
            if (markText(out.get(i).getAsJsonObject())) {
                left--;
            }
        }
        return out;
    }

    /** 最后一个工具放断点。 */
    static JsonArray markLastTool(JsonArray tools) {
        JsonArray out = tools.deepCopy();
        out.get(out.size() - 1).getAsJsonObject().add("cache_control", CacheBreakpoints.ephemeral());
        return out;
    }

    private static String role(JsonElement message) {
        JsonObject m = message.getAsJsonObject();
        return m.has("role") && m.get("role").isJsonPrimitive() ? m.get("role").getAsString() : "";
    }

    /** 给消息最后一个文字块放断点(字符串内容先换成单块数组);没有文字内容返回 false。 */
    private static boolean markText(JsonObject message) {
        JsonElement content = message.get("content");
        if (content != null && content.isJsonPrimitive()) {
            String text = content.getAsString();
            if (text.isEmpty()) return false;
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text);
            part.add("cache_control", CacheBreakpoints.ephemeral());
            JsonArray parts = new JsonArray();
            parts.add(part);
            message.add("content", parts);
            return true;
        }
        if (content != null && content.isJsonArray()) {
            JsonArray parts = content.getAsJsonArray();
            for (int i = parts.size() - 1; i >= 0; i--) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("type") && "text".equals(part.get("type").getAsString())) {
                    part.add("cache_control", CacheBreakpoints.ephemeral());
                    return true;
                }
            }
        }
        return false;
    }
}
