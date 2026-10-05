package com.dwinovo.numen.bench.report;

/** 一次评测怎么收场的。判据在运行器里,这里只是名字与给人看的一句。 */
public enum EndReason {
    /** 她自己停下了:没在跑的对话、没有后台活,也没有要她回应的输入。 */
    DONE("自己收工"),
    /** 自己收工,而收工前最后一个失败的工具结果是主人拒绝。 */
    DENIED("权限被拒"),
    /** 调模型的次数用完了。 */
    TURN_LIMIT("超轮数"),
    /** 游戏刻用完了。 */
    TICK_LIMIT("超游戏刻"),
    /** 墙钟用完了。 */
    WALL_LIMIT("超墙钟"),
    /** 调模型失败且不再重试,或端点不可用。 */
    API_ERROR("API 错"),
    /** 服务端说请求超出了上下文窗口。 */
    CONTEXT_OVERFLOW("超上下文"),
    /** 她死了。 */
    DIED("死亡"),
    /** 评测自己出了错(搭场景、驱动、断言本身抛出)。 */
    HARNESS_ERROR("评测出错");

    private final String words;

    EndReason(String words) {
        this.words = words;
    }

    /** 给人看的一句。 */
    public String words() {
        return words;
    }
}
