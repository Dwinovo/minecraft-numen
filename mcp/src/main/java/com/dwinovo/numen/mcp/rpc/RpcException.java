package com.dwinovo.numen.mcp.rpc;

/** 一次请求以 JSON-RPC 错误收场:处理请求的一方抛它,对方答复里的错误也以它交给等答复的人。 */
public final class RpcException extends Exception {

    private final RpcError error;

    public RpcException(RpcError error) {
        super(error.message() + " (" + error.code() + ")");
        this.error = error;
    }

    public RpcException(int code, String message) {
        this(new RpcError(code, message));
    }

    public RpcError error() {
        return error;
    }
}
