package com.dwinovo.numen.core.pathing.moves;

/**
 * 单个移动原语的执行状态机:
 * PREPPING(准备中,清障挖掘)→ WAITING(就绪待启动)→ RUNNING(执行中)
 * → 四种终态之一。
 */
public enum MovementStatus {

    /** 准备阶段:仍在挖掘挡路方块或等待环境就绪。 */
    PREPPING(false),
    /** 准备完成,等待开始执行。 */
    WAITING(false),
    /** 正在执行。 */
    RUNNING(false),
    /** 已到达终点。 */
    SUCCESS(true),
    /** 判定无法到达(如中途跌落出合法区)。 */
    UNREACHABLE(true),
    /** 执行失败。 */
    FAILED(true),
    /** 被外部取消。 */
    CANCELED(true);

    private final boolean complete;

    MovementStatus(boolean complete) {
        this.complete = complete;
    }

    /** 是否为终态(SUCCESS / UNREACHABLE / FAILED / CANCELED)。 */
    public boolean isComplete() {
        return complete;
    }
}
