package com.dwinovo.numen.mcp.rpc;

import java.io.IOException;

/**
 * 一条消息装不进这条路(超过单条消息的上限):路还通,只是这一条送不出去。{@link RpcPeer} 遇到它是在发答复时,就把答复换成一条
 * 说明这件事的 JSON-RPC 错误,让等答复的那一方当场知道、不必等到超时;别的 {@link java.io.IOException}(路断了)没有这个出路。
 */
public final class MessageTooBig extends IOException {

    public MessageTooBig(String message) {
        super(message);
    }
}
