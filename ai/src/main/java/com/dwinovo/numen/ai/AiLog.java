package com.dwinovo.numen.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * numen-ai 模块自己的日志句柄。本模块是纯 JVM 库(零 Minecraft、零 numen-api
 * 依赖),不能回头引用 api 侧的 Constants——日志名沿用同一个 "Numen",两侧输出
 * 在游戏日志里不可区分,行为与拆分前一致。
 */
public final class AiLog {

    public static final Logger LOG = LoggerFactory.getLogger("Numen");

    private AiLog() {}
}
