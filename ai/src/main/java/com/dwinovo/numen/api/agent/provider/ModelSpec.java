package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingTable;

/**
 * 一次请求里和型号有关的部分:协议实现编请求时需要知道的全部。
 *
 * @param id          型号 id(请求体里的 {@code model})
 * @param thinking    思考档位表;目录里查不到的型号是 {@link ThinkingTable#AUTO_ONLY}
 * @param temperature 型号收不收 {@code temperature};目录查不到按收处理(温度是玩家主动设的)
 */
public record ModelSpec(String id, ThinkingTable thinking, boolean temperature) {}
