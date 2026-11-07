package com.dwinovo.numen.mcp.server;

import java.util.List;
import java.util.UUID;

/**
 * 端点问宿主的全部:有哪些工具、调一次给什么正文、对客户端怎么介绍自己。端点(协议、传输、认人、限流、审计)不认识游戏,
 * 宿主不碰协议:工具的参数与结果在这一层只是文本(参数是 JSON 串,结果是几段文本)。
 *
 * <p>线程:任何线程(一次调用会占着一条线程等到做完,长等待的工具占几十秒)。
 */
public interface McpHost {

    /** 对客户端怎么介绍自己(initialize 的 serverInfo):图标是 PNG 的字节。 */
    record Identity(String name, String title, String description, String version, byte[] iconPng) {}

    /** 工具的性质(MCP 的工具注解),判据由宿主在工具定义处写明。 */
    record Hints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {}

    /**
     * 一个工具的定义。
     *
     * @param inputSchemaJson 参数的 JSON Schema(一个 JSON 对象的文本)
     * @param title           给人看的名字;没有是 null
     */
    record ToolInfo(String name, String title, String description, String inputSchemaJson, Hints hints) {}

    /**
     * 一次调用的结果。
     *
     * @param texts   按顺序的几段文本,每段一个文本块
     * @param error   这次调用算失败(给模型看,让它改正)
     * @param subject 这次调用作用的那一个对象(审计记它);没有是 null
     */
    record Result(List<String> texts, boolean error, UUID subject) {

        public static Result text(String text, boolean error) {
            return new Result(List.of(text), error, null);
        }
    }

    /** 调用进行中向客户端报进展(客户端带了进度令牌才会真的发)。 */
    @FunctionalInterface
    interface Progress {
        Progress NONE = message -> { };

        void report(String message);
    }

    Identity identity();

    /** 连上时给客户端的使用说明。 */
    String instructions();

    /** 这张令牌此刻看得到的工具。 */
    List<ToolInfo> tools(McpToken who) throws Exception;

    /**
     * 做一次调用。出岔子(超时、停服……)以异常抛出,端点按工具执行错误回给客户端。
     *
     * @param timeoutSeconds 配置里一次调用最久等多久
     */
    Result call(McpToken who, String tool, String argsJson, int timeoutSeconds, Progress progress) throws Exception;
}
