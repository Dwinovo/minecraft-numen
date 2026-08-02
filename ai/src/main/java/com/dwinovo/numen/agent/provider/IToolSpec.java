package com.dwinovo.numen.agent.provider;

import java.util.Map;

/**
 * 工具在 LLM 侧的只读描述面——provider 序列化 {@code tools} 数组所需的
 * 全部信息,连接层只认这三条。工具怎么执行、在哪个进程执行,是引擎侧
 * {@code NumenTool} 的事,连接层全盲。
 */
public interface IToolSpec {

    /** Tool name as the LLM sees it. {@code snake_case}. */
    String name();

    /**
     * Description shown to the LLM — the single biggest lever on whether the model
     * picks this tool correctly. Cover what it does, WHEN to use it (and when not),
     * what each non-obvious parameter means, and any caveat.
     */
    String description();

    /** JSON Schema (OpenAI tool-parameter dialect) for the tool's arguments. */
    Map<String, Object> parameterSchema();
}
