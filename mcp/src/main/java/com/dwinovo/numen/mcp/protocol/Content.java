package com.dwinovo.numen.mcp.protocol;

/** 工具结果里的一个内容块。 */
public sealed interface Content {

    record Text(String text) implements Content {}

    /** {@code data} 是 base64。 */
    record Image(String data, String mimeType) implements Content {}

    /** {@code data} 是 base64。 */
    record Audio(String data, String mimeType) implements Content {}

    /** 指向一个资源的链接(2025-06-18 起)。{@code mimeType} 没有是 null。 */
    record ResourceLink(String uri, String name, String mimeType) implements Content {}

    /** 内嵌的资源:文本的 {@code text} 非空,二进制的 {@code blob}(base64)非空;{@code mimeType} 没有是 null。 */
    record Resource(String uri, String mimeType, String text, String blob) implements Content {}
}
