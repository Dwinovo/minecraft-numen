package com.dwinovo.numen.api.agent.catalog;

import java.util.List;

/**
 * 目录里的一个型号。走哪个协议、连哪个地址在这里已经定好(型号自己写了就用它的,
 * 没写就是厂家的),查它的人不用再回头问厂家。
 *
 * @param contextWindow 上下文长度(token);目录没给是 0
 * @param maxOutput     输出上限(token);目录没给是 0
 * @param inputs        除文字以外能吃的输入模态(如 {@code image}、{@code pdf}),按目录里的顺序;能不能看图就看有没有 image
 * @param toolCall      能不能调工具
 * @param temperature   能不能调温度(目录没写按能处理:温度是玩家在档案里主动设的)
 * @param thinking      思考档位表
 * @param interleaved   思考内容夹在回复里时,放在哪个字段(如 {@code reasoning_content});没有是 null
 * @param cost          价格;目录没给是 null
 * @param api           协议
 * @param baseUrl       接口地址,可能为空(厂家没有公开固定地址)
 * @param releaseDate   发布日期,{@code 2025-03-15} 或只到月、年;目录没给是空串(按字典序比就是按时间比)
 */
public record ModelInfo(String id, String name, int contextWindow, int maxOutput, List<String> inputs,
                        boolean toolCall, boolean temperature, ThinkingTable thinking,
                        String interleaved, Cost cost, Api api, String baseUrl, String releaseDate) {

    public ModelInfo {
        inputs = List.copyOf(inputs);
    }

    /** 每百万 token 的美元价,缺的项是 0。 */
    public record Cost(double input, double output, double cacheRead, double cacheWrite) {}
}
