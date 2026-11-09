package com.dwinovo.numen.mcp.protocol;

import java.util.List;

/**
 * 一个图标(2025-11-25 起工具与 {@code Implementation} 可以带)。
 *
 * @param src      网址或 {@code data:} 网址
 * @param mimeType 没写是 null
 * @param sizes    如 {@code 48x48}、{@code any};没写是空
 */
public record Icon(String src, String mimeType, List<String> sizes) {

    public Icon {
        sizes = sizes == null ? List.of() : List.copyOf(sizes);
    }
}
