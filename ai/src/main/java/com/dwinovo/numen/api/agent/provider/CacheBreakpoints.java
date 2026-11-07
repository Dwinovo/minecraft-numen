package com.dwinovo.numen.api.agent.provider;

import com.google.gson.JsonObject;

/**
 * 显式缓存断点({@code cache_control})的放法,Anthropic Messages 与"网关转发给 Anthropic 型号的 Chat"
 * (OpenRouter 的 {@code anthropic/*})共用:缓存前缀的顺序是 工具 → system → 消息,断点各放一个在
 * 最后一个工具和 system 上,消息里再放 {@link #HISTORY} 个。
 *
 * <p>pi-mono 在消息里只放一个(最后一条用户消息的末块)。我们放两个,因为每一轮请求的末尾都会再追加
 * 一条现算、不进历史的 {@code <runtime_state>}:最后一块下一轮就变了,断点落在上面白写一次缓存;
 * 倒数第二块是历史的末尾,下一轮还在原处,服务端回看时从它命中整段历史。
 */
final class CacheBreakpoints {

    /** 消息里从末尾往前放几个断点。 */
    static final int HISTORY = 2;

    private CacheBreakpoints() {}

    /** 缓存 5 分钟(协议缺省的存活时间)。 */
    static JsonObject ephemeral() {
        JsonObject c = new JsonObject();
        c.addProperty("type", "ephemeral");
        return c;
    }
}
