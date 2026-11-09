package com.dwinovo.numen.mcp.protocol;

import java.util.List;

/**
 * 一端的自我介绍(initialize 里的 {@code clientInfo} 与 {@code serverInfo})。
 *
 * @param title       给人看的名字;没有是 null
 * @param description 一句介绍;没有是 null
 */
public record Implementation(String name, String title, String version, String description, List<Icon> icons) {

    public Implementation {
        icons = icons == null ? List.of() : List.copyOf(icons);
    }
}
