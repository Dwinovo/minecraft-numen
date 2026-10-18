package com.dwinovo.numen.api.agent.tool;

import java.util.List;

/**
 * 一只同伴看得到的工具:系统自带的与插件的(全局,{@link ToolRegistry}),加上她主人自己接进来的 MCP 工具,减去主人关掉的。
 * 每一轮请求发哪些工具、模型点了名字去哪找,都问它——"她有哪些工具"只有这一个出处。
 */
public interface ToolBook {

    /** 这一轮发出去的工具,按登记顺序。 */
    List<NumenTool> all();

    /** 模型点名的工具;没有这个工具是 {@code null}。 */
    NumenTool resolve(String name);
}
