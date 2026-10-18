package com.dwinovo.numen.api.agent.loop;

/**
 * 切断的原因。主人停止、死亡、登出、外接接管、遣散走同一个 {@link AgentLoop#halt},差别只在这张表:
 *
 * <pre>
 * reason      触发                  叫停身体  清被取代的指令             长期目标  之后的停牌
 * OWNER_STOP  停止键                是        是(忙闲都清,含排着的 goal) 收工      OWNER_STOP
 * DEATH       死亡包                否(已死)  否                         保留      DEAD
 * REMOVED     身体被外面请出服务器  否(已不在) 否                         保留      DEAD
 * DISCONNECT  登出、断线            否        否                         保留      不变
 * EXTERNAL    驾驶翻转为外接        否        否                         保留      不变(现算 EXTERNAL)
 * DISPOSE     遣散、RESET_LOOPS     否        否(队列随同伴一起删)       保留      不变
 * </pre>
 *
 * <p>登出不叫停身体:她还在服务器里跑,收尾进她的收件箱等主人回来;也不删目标、不置停牌,
 * 攒在收件箱里的 {@code task_finished} 回来照样叫得醒她。
 */
public enum HaltReason {
    OWNER_STOP("halt.owner_stop", "被主人打断", true, true, true, Hold.OWNER_STOP),
    DEATH("halt.death", "你死了", false, false, false, Hold.DEAD),
    REMOVED("halt.removed", "你的身体被请出了服务器", false, false, false, Hold.DEAD),
    DISCONNECT("halt.disconnect", "主人断线了", false, false, false, null),
    EXTERNAL("halt.external", "外接模型接管了身体", false, false, false, null),
    DISPOSE("halt.dispose", "大脑被重置", false, false, false, null);

    private final String code;
    private final String words;
    private final boolean stopsBody;
    private final boolean clearsInterrupted;
    private final boolean endsGoal;
    private final Hold enters;

    HaltReason(String code, String words, boolean stopsBody, boolean clearsInterrupted, boolean endsGoal, Hold enters) {
        this.code = code;
        this.words = words;
        this.stopsBody = stopsBody;
        this.clearsInterrupted = clearsInterrupted;
        this.endsGoal = endsGoal;
        this.enters = enters;
    }

    /** 结构化的原因:码 + 现场的话(死因等,没有就空),界面按当前语言说。 */
    public Cause cause(String detail) {
        return detail == null || detail.isBlank() ? Cause.of(code) : Cause.of(code, detail);
    }

    /** 切断点上给模型看的原因;{@code detail}(死因等)非空时跟在括号里。 */
    public String words(String detail) {
        return detail == null || detail.isBlank() ? words : words + "(" + detail + ")";
    }

    /** 要不要连身体一起叫停。 */
    public boolean stopsBody() {
        return stopsBody;
    }

    /** 要不要清掉队列里被取代的指令(类型表 {@code clearedByInterrupt} 的那些)。 */
    public boolean clearsInterrupted() {
        return clearsInterrupted;
    }

    /** 长期目标要不要跟着收工。目标归门面管,它读这一列。 */
    public boolean endsGoal() {
        return endsGoal;
    }

    /** 之后进入的停牌;{@code null} = 停牌不变。 */
    Hold enters() {
        return enters;
    }
}
