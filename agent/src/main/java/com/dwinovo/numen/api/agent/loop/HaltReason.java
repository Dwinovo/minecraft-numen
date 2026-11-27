package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.llm.Rows;

/**
 * 切断的原因。主人停止、死亡、主人登出、她登出、外接接管、遣散走同一个 {@link AgentLoop#halt},差别只在这张表:
 *
 * <pre>
 * reason      触发                  叫停身体  清被取代的指令             之后的停牌
 * OWNER_STOP  停止键                是        是(忙闲都清,含排着的 goal) OWNER_STOP
 * DEATH       死亡包                否(已死)  否                         DEAD
 * REMOVED     身体被外面请出服务器  否(已不在) 否                         DEAD
 * OFFLINE     身体登出世界          否(已不在) 否                         DEAD
 * DISCONNECT  主人登出、断线        否        否                         不变
 * EXTERNAL    驾驶翻转为外接        否        否                         不变(现算 EXTERNAL)
 * DISPOSE     遣散、RESET_LOOPS     否        否(队列随同伴一起删)       不变
 * </pre>
 *
 * <p>长期目标哪一种切断都不清(照 Claude Code,打断不清目标)。主人登出这一刀不叫停身体(她登出世界是另一回事,见 OFFLINE):
 * 收尾进她的收件箱等主人回来;也不置停牌,攒在收件箱里的 {@code task_finished} 回来照样叫得醒她。
 */
public enum HaltReason {
    OWNER_STOP(Rows.OWNER_STOP_CODE, Rows.OWNER_STOP_WORDS, true, true, Hold.OWNER_STOP),
    DEATH("halt.death", "you died", false, false, Hold.DEAD),
    REMOVED("halt.removed", "your body was removed from the server", false, false, Hold.DEAD),
    OFFLINE("halt.offline", "you went offline", false, false, Hold.DEAD),
    DISCONNECT("halt.disconnect", "the owner disconnected", false, false, null),
    EXTERNAL("halt.external", "an external model took over the body", false, false, null),
    DISPOSE("halt.dispose", "the brain was reset", false, false, null);

    private final String code;
    private final String words;
    private final boolean stopsBody;
    private final boolean clearsInterrupted;
    private final Hold enters;

    HaltReason(String code, String words, boolean stopsBody, boolean clearsInterrupted, Hold enters) {
        this.code = code;
        this.words = words;
        this.stopsBody = stopsBody;
        this.clearsInterrupted = clearsInterrupted;
        this.enters = enters;
    }

    /** 原因码({@code halt.owner_stop} 等):插件事件 {@code RUN_ENDED} 的 reason 就是它。 */
    public String code() {
        return code;
    }

    /** 结构化的原因:码 + 现场的话(死因等,没有就空),界面按当前语言说。 */
    public Cause cause(String detail) {
        return detail == null || detail.isBlank() ? Cause.of(code) : Cause.of(code, detail);
    }

    /** 切断点上给模型看的原因;{@code detail}(死因等)非空时跟在括号里。 */
    public String words(String detail) {
        return detail == null || detail.isBlank() ? words : words + " (" + detail + ")";
    }

    /** 要不要连身体一起叫停。 */
    public boolean stopsBody() {
        return stopsBody;
    }

    /** 要不要清掉队列里被取代的指令(类型表 {@code clearedByInterrupt} 的那些)。 */
    public boolean clearsInterrupted() {
        return clearsInterrupted;
    }

    /** 之后进入的停牌;{@code null} = 停牌不变。 */
    Hold enters() {
        return enters;
    }
}
