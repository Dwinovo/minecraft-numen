package com.dwinovo.numen.mcp.rpc;

import com.google.gson.JsonElement;

/**
 * JSON-RPC 2.0 的错误对象。错误码只在这里定义。
 *
 * @param data 附带的数据;没有是 null
 */
public record RpcError(int code, String message, JsonElement data) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public RpcError(int code, String message) {
        this(code, message, null);
    }
}
