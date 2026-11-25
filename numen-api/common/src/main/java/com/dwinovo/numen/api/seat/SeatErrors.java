package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.mcp.rpc.RpcException;

/** 座位上请求被拒时的 JSON-RPC 错误:码取自规范留给应用的 -32000 段,说明写给调试的人,英文。 */
final class SeatErrors {

    /** 这只同伴不存在,或不是这条连接的玩家创建的。 */
    static final int NOT_YOURS = -32001;
    /** 这条连接没有坐着她的座位:先 {@link SeatMethods#SEAT_OPEN}。 */
    static final int NO_SEAT = -32002;
    /** 她的身体换过一次了:脑子以为的代数对不上。 */
    static final int STALE_GENERATION = -32003;

    private SeatErrors() {}

    static RpcException notYours(java.util.UUID companion) {
        return new RpcException(NOT_YOURS, "companion " + companion + " does not exist or was not created by you; "
                + "only her creator's connection may open her seat");
    }

    static RpcException noSeat(java.util.UUID companion) {
        return new RpcException(NO_SEAT, "the seat of companion " + companion + " is not open on this connection; "
                + "send " + SeatMethods.SEAT_OPEN + " first");
    }

    static RpcException staleGeneration(long believed, long actual) {
        return new RpcException(STALE_GENERATION, "her body is at generation " + actual + ", not " + believed
                + ": she entered the world again since you last heard from her, so this is not the body you meant");
    }
}
