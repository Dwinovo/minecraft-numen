package com.dwinovo.numen.mcp.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 我们说的 MCP 版本,与各版本之间线格式的差异。版本协商与"这个版本有没有这个字段"只在这里回答,别处问它,不各自比日期。
 *
 * <p>主版本是 {@link #LATEST}(2025-11-25);对方要 2025-06-18 或 2025-03-26 的照它说。2024-11-05(旧的 HTTP+SSE 传输)不说。
 * 差异逐条对过各版本的 {@code schema.ts} 与 {@code basic/transports.mdx}。
 */
public enum ProtocolVersion {

    V2025_03_26("2025-03-26"),
    V2025_06_18("2025-06-18"),
    V2025_11_25("2025-11-25");

    public static final ProtocolVersion LATEST = V2025_11_25;

    /** Streamable HTTP:服务端既没收到版本头、又没有别的办法知道版本时假定的版本(transports 的 Protocol Version Header 一节)。 */
    public static final ProtocolVersion ASSUMED = V2025_03_26;

    private final String wire;

    ProtocolVersion(String wire) {
        this.wire = wire;
    }

    /** 线上的写法,如 {@code 2025-11-25}。 */
    public String wire() {
        return wire;
    }

    public static Optional<ProtocolVersion> of(String wire) {
        return Arrays.stream(values()).filter(v -> v.wire.equals(wire)).findFirst();
    }

    /** 服务端的版本协商:对方要的我们支持就用它,否则答我们最新的(lifecycle 的 Version Negotiation 一节)。 */
    public static ProtocolVersion negotiate(String requested) {
        return of(requested).orElse(LATEST);
    }

    public boolean atLeast(ProtocolVersion other) {
        return ordinal() >= other.ordinal();
    }

    /** 一个 POST 里可以是一批(JSON 数组)消息:只有 2025-03-26 允许,2025-06-18 起去掉了。 */
    public boolean batching() {
        return this == V2025_03_26;
    }

    /** 工具与 {@code Implementation} 有给人看的 {@code title}(2025-06-18 起)。 */
    public boolean titles() {
        return atLeast(V2025_06_18);
    }

    /** 有 elicitation(2025-06-18 起)。 */
    public boolean elicitation() {
        return atLeast(V2025_06_18);
    }

    /** 工具与 {@code Implementation} 有 {@code icons},{@code Implementation} 有 {@code description}(2025-11-25 起)。 */
    public boolean icons() {
        return atLeast(V2025_11_25);
    }

    /**
     * elicitation 分表单与网址两种:请求里有 {@code mode},客户端能力里分 {@code form}/{@code url}(2025-11-25 起;此前只有表单,
     * 能力是一个空对象)。
     */
    public boolean elicitationModes() {
        return atLeast(V2025_11_25);
    }

    /**
     * 表单里带标题的单选写成 {@code oneOf} 的一串 {@code const}/{@code title}(2025-11-25 起);此前写成 {@code enum} 加同样长的
     * {@code enumNames}。
     */
    public boolean titledEnumsAsOneOf() {
        return atLeast(V2025_11_25);
    }
}
