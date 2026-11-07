package com.dwinovo.numen.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;

/**
 * 服务端与客户端共用的 JSON 映射器与 schema 校验器,在这里显式构造、显式交给 SDK 的各个构建器。
 * SDK 不传它们时用 ServiceLoader 按线程上下文类加载器去找实现;Paper 插件的类在插件自己的类加载器里,
 * 服务器线程的上下文类加载器看不到,那条路在 Paper 上找不到实现。显式构造不依赖运行时的服务发现,三个平台一样。
 */
public final class McpJson {
    public static final McpJsonMapper MAPPER = new JacksonMcpJsonMapperSupplier().get();
    public static final JsonSchemaValidator SCHEMA_VALIDATOR = new JacksonJsonSchemaValidatorSupplier().get();

    private McpJson() {
    }
}
