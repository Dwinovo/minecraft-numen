package com.dwinovo.numen.mcp.client;

import java.util.Set;

/**
 * 从 MCP server 借来的工具，叫什么名字。
 *
 * <h2>为什么名字要重写而不是照搬</h2>
 * 工具清单<b>每一轮都要发</b>给模型。名字里有一个上游不认的字符，被打回的不是这个工具，
 * 是整个请求——也就是这个同伴从此每句话都失败。而 MCP 那边的命名习惯跟这边不一样：
 * {@code browser.navigate} 这种带点的很常见，服务名再长一点就轻松超过上限。
 *
 * <p>所以这里管三件事，缺一不可:
 * <ul>
 *   <li><b>字符</b>——只留 {@code [a-z0-9_]},其余一律换成下划线;</li>
 *   <li><b>长度</b>——连分隔符一起不超过 {@value #MAX_LENGTH};超了截断远端那半边并缀上
 *       内容哈希,不同的长名字截断后仍然分得开;</li>
 *   <li><b>唯一</b>——洗过之后 {@code my.server} 和 {@code my_server} 会撞成同一个,
 *       撞了就加序号。重名在注册表那边是抛异常的,不能指望它兜底。</li>
 * </ul>
 *
 * <p>原始的远端名字不受影响——真正调用 MCP 时用的是那个,见 {@link RemoteMcpTool}。
 *
 * <p>纯函数,不碰网络也不碰 Minecraft。
 */
public final class McpToolName {

    /** 上游对函数名的长度上限。 */
    public static final int MAX_LENGTH = 64;
    /** 服务名与工具名之间的分隔符。 */
    public static final String SEPARATOR = "__";
    /** 截断时缀在后面的哈希长度(不含前导下划线)。 */
    private static final int HASH_LENGTH = 8;

    private McpToolName() {}

    /**
     * 拼出这个工具在模型那边的名字。
     *
     * @param taken 本批里已经用掉的名字;本方法<b>会把结果加进去</b>,调用方按顺序传同一个集合即可
     */
    public static String qualify(String serverName, String remoteName, Set<String> taken) {
        String server = sanitize(serverName);
        String tool = sanitize(remoteName);
        String name = fit(server, tool);
        String unique = name;
        for (int n = 2; !taken.add(unique); n++) {
            unique = withSuffix(name, String.valueOf(n));
        }
        return unique;
    }

    /** 只留 {@code [a-z0-9_]};空串兜底成一个下划线,免得拼出 {@code __tool} 这种前导分隔符。 */
    static String sanitize(String s) {
        String clean = s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return clean.isEmpty() ? "_" : clean;
    }

    /** 拼到长度以内。超了就截远端那半边,并缀上原名的哈希——截断后仍然分得开。 */
    private static String fit(String server, String tool) {
        String full = server + SEPARATOR + tool;
        if (full.length() <= MAX_LENGTH) {
            return full;
        }
        // 服务名自己就把预算吃光时,它也得让位:留给工具名至少一个哈希的位置
        int budget = MAX_LENGTH - SEPARATOR.length() - HASH_LENGTH - 1;
        int forServer = Math.min(server.length(), Math.max(1, budget / 2));
        int forTool = Math.max(1, budget - forServer);
        return server.substring(0, forServer) + SEPARATOR
                + withSuffix(tool.substring(0, Math.min(tool.length(), forTool)), hash(tool));
    }

    /** 在名字尾部缀上 {@code _suffix},必要时把前面截掉,保证总长不越界。 */
    private static String withSuffix(String name, String suffix) {
        String tail = "_" + suffix;
        int keep = Math.max(1, MAX_LENGTH - tail.length());
        return (name.length() <= keep ? name : name.substring(0, keep)) + tail;
    }

    /** 内容哈希。只为把截断后的同前缀名字分开,不做安全用途。 */
    private static String hash(String s) {
        String hex = Integer.toHexString(s.hashCode());
        return hex.length() >= HASH_LENGTH ? hex.substring(0, HASH_LENGTH)
                : "0".repeat(HASH_LENGTH - hex.length()) + hex;
    }
}
