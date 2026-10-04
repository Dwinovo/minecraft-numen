package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.script.ErrorKind;

/**
 * 一件身体活交回的结果:成没成、它做了什么的实际账、值,失败时还有种类与能照抄的下一步。
 *
 * <h2>账与值</h2>
 * {@code message} 是实际账(走了几步、挖了什么、在哪停下),整段写进等它的程序的回执,程序停下后才收尾的写进 task_finished;
 * {@code value} 是交给程序的值,类型是派它的那个 API 函数声明的返回类型({@code Job<R>} / {@code Pending<R>} 的 {@code R}),由派发
 * 按那个类型写成脚本的值。失败时 {@code value} 是失败时知道的东西(够不着的最近一格这类),写进错误值的 {@code data}。
 *
 * <h2>失败</h2>
 * {@code kind} 说是哪一类({@link ErrorKind}),脚本 {@code pcall} 后按它分支;{@code hint} 是能照抄的下一行程序。期限到了是
 * {@link ErrorKind#TIMEOUT},被叫停是 {@link ErrorKind#INTERRUPTED}。
 *
 * @param success 做成了没有
 * @param message 它做了什么的实际账
 * @param value   成功时交给程序的值,失败时失败时知道的东西;没有是 null
 * @param kind    失败的种类;成功是 null
 * @param hint    失败时能照抄的下一步;没有是 null
 */
public record TaskResult(boolean success, String message, Object value, ErrorKind kind, String hint) {

    public TaskResult {
        if (success == (kind != null)) {
            throw new IllegalArgumentException("a failed result names its kind, a successful one has none");
        }
    }

    /** 做成了,交回 {@code value}。 */
    public static TaskResult ok(String message, Object value) {
        return new TaskResult(true, message, value, null, null);
    }

    /** 做成了,不交回值。 */
    public static TaskResult ok(String message) {
        return ok(message, null);
    }

    /** 没做成,种类是 {@link ErrorKind#FAILED}。 */
    public static TaskResult fail(String message) {
        return fail(ErrorKind.FAILED, message, null, null);
    }

    /** 没做成,说清种类;{@code hint} 是能照抄的下一步(没有为 null)。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint) {
        return fail(kind, message, hint, null);
    }

    /** 没做成,说清种类,带上失败时知道的东西。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint, Object data) {
        return new TaskResult(false, message, data, kind, hint);
    }

    /** 到了期限还没做完,带上做到哪的值。 */
    public static TaskResult timeout(String message, Object data) {
        return new TaskResult(false, message, data, ErrorKind.TIMEOUT, null);
    }

    public static TaskResult timeout(String message) {
        return timeout(message, null);
    }

    /** 被叫停了,带上做到哪的值。 */
    public static TaskResult cancelled(String message, Object data) {
        return new TaskResult(false, message, data, ErrorKind.INTERRUPTED, null);
    }

    public static TaskResult cancelled(String message) {
        return cancelled(message, null);
    }

    /** 到了期限。 */
    public boolean timedOut() {
        return kind == ErrorKind.TIMEOUT;
    }

    /** 被叫停了。 */
    public boolean interrupted() {
        return kind == ErrorKind.INTERRUPTED;
    }

    /** 同一份结果,账前面写上是谁叫停的({@link TaskRecord.StopCause})。 */
    public TaskResult stoppedBy(TaskRecord.StopCause cause) {
        return new TaskResult(success, cause.words() + " — " + message, value, kind, hint);
    }
}
