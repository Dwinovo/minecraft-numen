package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 等一会儿才有的值:等主人答复(过权限层)、等下一刻(按刻分片的搜索)、等一件有界的短身体活({@link ServerCall#sync})。不占任务槽,
 * 程序在这次调用上等着,值或失败到了才往下走。只在服务端线程上完成。
 *
 * <p>API 往 stderr 报告的唯一入口是 {@link #report}(占身体的活是 {@link Job},它的实际账是任务收尾的话):身体在等的这段时间里做了什么
 * (短活的实际账、主人点头允许了什么)、一次查询没看全哪里,随值一起交回,写进程序回执的 stderr 一栏——身体动过的事都要让她知道。
 * 像命令行工具自己决定往 stderr 写什么:返回值是给程序用的,只读的查询通常什么都不报告,查到的东西在值里。
 *
 * <pre>{@code
 * Pending<Found> p = Pending.create();
 * BlockScan.start(her, radius, targets, found -> p.complete(Found.of(found)));
 * return p;
 * }</pre>
 *
 * @param <R> 等到的值
 */
public final class Pending<R> {

    private Consumer<R> onValue;
    private Consumer<ApiError> onError;
    private boolean done;
    private R value;
    private ApiError error;
    /** 等的这段时间里 API 报告的话;什么都没有是空串。 */
    private String stderr = "";

    private Pending() {}

    /** 一个还没有结论的。 */
    public static <R> Pending<R> create() {
        return new Pending<>();
    }

    /** 已经有值的。 */
    public static <R> Pending<R> of(R value) {
        Pending<R> p = new Pending<>();
        p.complete(value);
        return p;
    }

    /** 已经失败的。 */
    public static <R> Pending<R> failed(ApiError error) {
        Pending<R> p = new Pending<>();
        p.fail(error);
        return p;
    }

    /** 有值了。只能有一次结论。 */
    public synchronized void complete(R value) {
        settle(value, null);
    }

    /** 失败了。只能有一次结论。 */
    public synchronized void fail(ApiError error) {
        settle(null, error);
    }

    private void settle(R value, ApiError error) {
        if (done) {
            throw new IllegalStateException("a Pending has one outcome; it already has "
                    + (this.error != null ? "failed: " + this.error.getMessage() : "a value"));
        }
        done = true;
        this.value = value;
        this.error = error;
        if (onValue != null) {
            deliver();
        }
    }

    /**
     * 有了值之后接着做 {@code next},它的结果是新的值;它抛的 {@link ApiError} 是这一次的失败。前一步失败就不做,失败原样传下去。
     * 前一步的账带过去。
     */
    public <S> Pending<S> then(Function<R, S> next) {
        Pending<S> out = new Pending<>();
        whenDone(v -> {
            S s;
            try {
                s = next.apply(v);
            } catch (ApiError failed) {
                out.fail(failed);
                return;
            }
            out.stderr = stderr;
            out.complete(s);
        }, out::fail);
        return out;
    }

    /** 有了值之后接着等 {@code next} 交出的那一个。前一步失败就不做,失败原样传下去。两步的账接在一起。 */
    public <S> Pending<S> thenWait(Function<R, Pending<S>> next) {
        Pending<S> out = new Pending<>();
        whenDone(v -> {
            Pending<S> then;
            try {
                then = next.apply(v);
            } catch (ApiError failed) {
                out.fail(failed);
                return;
            }
            then.whenDone(s -> {
                out.stderr = joined(stderr, then.stderr);
                out.complete(s);
            }, out::fail);
        }, out::fail);
        return out;
    }

    /**
     * 往 stderr 报告这段等待里要让她知道的事:身体做了什么、主人允许了什么、没看全哪里。随值交回,写进程序的回执。在 {@link #complete}
     * 之前报告;再报告一次换掉前一次。
     */
    public synchronized void report(String words) {
        stderr = words == null ? "" : words.strip();
    }

    /** 这段等待里 API 报告的话;什么都没有是空串。 */
    synchronized String stderr() {
        return stderr;
    }

    private static String joined(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b;
    }

    /** 有结论时调这两个之一,恰好一次;已经有了就当场调。 */
    synchronized void whenDone(Consumer<R> onValue, Consumer<ApiError> onError) {
        if (this.onValue != null) {
            throw new IllegalStateException("a Pending is waited for once");
        }
        this.onValue = onValue;
        this.onError = onError;
        if (done) {
            deliver();
        }
    }

    private void deliver() {
        if (error != null) {
            onError.accept(error);
        } else {
            onValue.accept(value);
        }
    }

    /** 不等了(这次调用被叫停):还没结论就以 {@link ErrorKind#INTERRUPTED} 失败。 */
    synchronized void interrupt(String why) {
        if (!done) {
            settle(null, new ApiError(ErrorKind.INTERRUPTED, why, null));
        }
    }
}
