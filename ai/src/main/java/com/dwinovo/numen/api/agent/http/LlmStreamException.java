package com.dwinovo.numen.api.agent.http;

/**
 * 流已经开始(HTTP 2xx),却以失败收场:流里带了错误帧,或者没有等到协议约定的收尾事件就断了。
 * 消息是厂家在流里说的原话(或我们看到的情形),不带状态码——它不是 HTTP 层的拒绝,归因时与 {@link LlmHttpException} 分开。
 */
public final class LlmStreamException extends RuntimeException {

    public LlmStreamException(String message) {
        super(message);
    }
}
