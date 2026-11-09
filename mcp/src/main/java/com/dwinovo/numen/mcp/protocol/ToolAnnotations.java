package com.dwinovo.numen.mcp.protocol;

/**
 * 工具的性质(MCP 的工具注解)。没声明的是 null:对方没说,按规范的缺省(会改东西、可能毁东西、不幂等、触及外面)由用的一方理解。
 */
public record ToolAnnotations(Boolean readOnlyHint, Boolean destructiveHint, Boolean idempotentHint, Boolean openWorldHint) {
}
