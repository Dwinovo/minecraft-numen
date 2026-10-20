package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.task.TaskRecord;

/**
 * 占身体的活:进她唯一的任务槽(顶掉手上那件),受理时回活的编号,程序等它收尾才拿到值;收尾的实际账整段写进程序的回执。受理之前先
 * 准备({@code Task#prepare}),准备不过当场失败、不占槽。重启后接着做:受理时把这次调用写成一行 Lua 记下,重启后再跑一遍。
 *
 * <pre>{@code
 * return Job.of(new AttackTaskRecord(call, ...));
 * }</pre>
 *
 * <p>活是 {@code TaskRecord<R>}(或交回 {@code R} 的一种的):它收尾时交回的值是 {@code R},派发按这个函数的返回类型把它写成脚本的值。
 *
 * @param <R> 收尾时的值
 */
public final class Job<R> {

    private final TaskRecord<? extends R> record;
    private final R done;

    private Job(TaskRecord<? extends R> record, R done) {
        this.record = record;
        this.done = done;
    }

    /** 这件活。 */
    public static <R> Job<R> of(TaskRecord<? extends R> record) {
        return new Job<>(record, null);
    }

    /**
     * 没有活要干:要的样子此刻已经是了(要盖的都已立着)。这次调用当场交回 {@code value},不派活、不顶掉手上那件。
     */
    public static <R> Job<R> done(R value) {
        return new Job<>(null, value);
    }

    /** 这件活;{@link #done} 的是 null。 */
    TaskRecord<? extends R> record() {
        return record;
    }

    /** {@link #done} 当场交回的值。 */
    R done() {
        return done;
    }
}
