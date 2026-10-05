package com.dwinovo.numen.sdk;

import java.util.UUID;

/**
 * 主人客户端上的一次调用:她是谁、调的是哪个函数。客户端函数拿到它,当场答。要她的循环、界面这类只活在客户端的东西,拿 UUID 去
 * 客户端那一侧的登记处取——公共代码摸不到客户端类。
 *
 * <h2>写客户端函数的约定</h2>
 * 程序在服务端跑,遇到客户端函数就向主人的客户端发反向请求、挂着等答复。登记时不检查这些,写法好不好由评测的分数说话,但照这样写
 * 才不卡程序、不出错:
 * <ul>
 *   <li><b>只读查询,不产生副作用。</b>主人的客户端在 {@link com.dwinovo.numen.program.ProgramLimits#CLIENT_ANSWER_TICKS} 刻内没答复,
 *       这次调用以 {@code timeout} 失败、程序接着往下走;失败或超时后原样再调一次必须是安全的(幂等)。</li>
 *   <li><b>客户端的答复不可信。</b>主人的客户端是玩家的机器,答复可以被改;权限层与任何裁决不读客户端函数的返回值,只读服务端自己
 *       知道的。</li>
 *   <li><b>只是通知主人的事不用它。</b>通知不要答复,走单向事件({@link com.dwinovo.numen.api.NumenApi#emit},服务端发 {@code NumenEventPayload}),
 *       程序不挂着等。</li>
 *   <li><b>一次接收一批键,在客户端就地过滤,只回小结果。</b>在循环里逐条问(N+1)每条一次网络往返;写成接收一张键表、在客户端把
 *       不要的滤掉再答。</li>
 * </ul>
 */
public final class ClientCall {

    private final UUID companion;
    private final ApiFunction function;
    private final Record args;

    ClientCall(UUID companion, ApiFunction function, Record args) {
        this.companion = companion;
        this.function = function;
        this.args = args;
    }

    /** 她是谁。 */
    public UUID companion() {
        return companion;
    }

    /** 这次调用写成的那一行 Lua。 */
    public String lua() {
        return Call.of(function, args);
    }
}
