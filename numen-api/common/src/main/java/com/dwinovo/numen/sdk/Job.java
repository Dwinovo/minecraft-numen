package com.dwinovo.numen.sdk;

import com.dwinovo.numen.task.TaskRecord;

/**
 * 占身体的活:进她唯一的任务槽(顶掉手上那件),受理时回活的编号,程序等它收尾才拿到值;收尾的实际账整段写进程序的回执。受理之前先
 * 准备({@code Task#prepare}),准备不过当场失败、不占槽。重启后接着做:受理时把这次调用写成一行 Lua 记下,重启后再跑一遍。
 *
 * <pre>{@code
 * return Job.of(new AttackTaskRecord(call, ...)).replayedAs(new Attack(EntityRef.of(target)));
 * }</pre>
 *
 * <p>活收尾时交回的值({@code TaskResult} 的值)必须是 {@code R}:派发按这个函数的返回类型把它写成脚本的值。
 *
 * @param <R> 收尾时的值
 */
public final class Job<R> {

    private final TaskRecord record;
    private final Record replay;
    private final R done;

    private Job(TaskRecord record, Record replay, R done) {
        this.record = record;
        this.replay = replay;
        this.done = done;
    }

    /** 这件活。 */
    public static <R> Job<R> of(TaskRecord record) {
        return new Job<>(record, null, null);
    }

    /**
     * 没有活要干:要的样子此刻已经是了(要盖的都已立着)。这次调用当场交回 {@code value},不派活、不顶掉手上那件。
     */
    public static <R> Job<R> done(R value) {
        return new Job<>(null, null, value);
    }

    /**
     * 重启后再跑的那一次调用用这份参数写,不用这次的:只在这一次开服里有效的写法(实体的运行期编号)换成跨重启不变的
     * ({@link EntityRef#of},按 UUID 认)。
     */
    public Job<R> replayedAs(Record args) {
        return new Job<>(record, args, done);
    }

    /** 这件活;{@link #done} 的是 null。 */
    TaskRecord record() {
        return record;
    }

    /** {@link #done} 当场交回的值。 */
    R done() {
        return done;
    }

    /** 重启后再跑时的参数;没换是 null(用这次调用的)。 */
    Record replay() {
        return replay;
    }
}
