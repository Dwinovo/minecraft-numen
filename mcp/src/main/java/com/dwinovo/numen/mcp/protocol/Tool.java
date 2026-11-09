package com.dwinovo.numen.mcp.protocol;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 一个工具的定义({@code tools/list} 里的一项)。
 *
 * @param title       给人看的名字;没有是 null
 * @param description 没有是 null
 * @param inputSchema 参数的 JSON Schema,原样
 * @param annotations 没有是 null
 */
public record Tool(String name, String title, String description, JsonObject inputSchema, ToolAnnotations annotations,
                   List<Icon> icons) {

    public Tool {
        icons = icons == null ? List.of() : List.copyOf(icons);
    }
}
