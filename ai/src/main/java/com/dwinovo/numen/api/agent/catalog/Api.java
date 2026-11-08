package com.dwinovo.numen.api.agent.catalog;

/**
 * 线协议:请求怎么编、流式返回怎么解。三种,各由 {@code provider} 包里一个实现承载;
 * 厂家之间的差异不在这里,在各协议自己的兼容项里。
 */
public enum Api {
    /** Chat Completions(含绝大多数 OpenAI 兼容端点)。 */
    CHAT("chat"),
    /** OpenAI Responses。 */
    RESPONSES("responses"),
    /** Anthropic Messages。 */
    ANTHROPIC("anthropic");

    private final String id;

    Api(String id) {
        this.id = id;
    }

    /** 存档与目录里的写法。 */
    public String id() {
        return id;
    }

    /** 按存档里的写法找协议;空或不认识给 {@code null}。 */
    public static Api byId(String id) {
        if (id != null) {
            for (Api a : values()) {
                if (a.id.equals(id.strip())) return a;
            }
        }
        return null;
    }

    /**
     * models.dev 的 {@code npm} 字段决定协议(同 pi-mono):{@code @ai-sdk/openai} 走 Responses,
     * {@code @ai-sdk/anthropic} 走 Anthropic Messages,其余——含 {@code @ai-sdk/openai-compatible}——走 Chat。
     */
    public static Api ofNpm(String npm) {
        if ("@ai-sdk/openai".equals(npm)) return RESPONSES;
        if ("@ai-sdk/anthropic".equals(npm)) return ANTHROPIC;
        return CHAT;
    }
}
