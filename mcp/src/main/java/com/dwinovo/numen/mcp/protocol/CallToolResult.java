package com.dwinovo.numen.mcp.protocol;

import com.google.gson.JsonElement;

import java.util.List;

/**
 * {@code tools/call} 的结果。
 *
 * @param structuredContent 结构化的结果(2025-06-18 起);没有是 null
 * @param isError           这次调用算失败(给模型看,让它改正)
 */
public record CallToolResult(List<Content> content, JsonElement structuredContent, boolean isError) {

    public CallToolResult {
        content = List.copyOf(content);
    }
}
