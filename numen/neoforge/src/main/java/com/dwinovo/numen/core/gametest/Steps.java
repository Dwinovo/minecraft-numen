package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 一条用例的步骤:等到某件事成立、做一件事、空等几刻、算通过。用例一律经它({@link GameTestKit#steps}、
 * {@link GameTestKit#succeedWhen}),不直接用原版的 {@code helper.startSequence()} 与 {@code helper.succeedWhen}。
 *
 * <h2>为什么不用原版的序列</h2>
 * 原版 {@code GameTestSequence} 的两处做法,让一条用例的失败变成整台测试服的崩溃:
 * <ul>
 *   <li>{@code thenExecute} 里断言失败只记下失败,不停下:同一刻接着跑后面的步骤,而后面的步骤读的正是失败那一步
 *       没来得及设好的东西(一个还是 null 的引用),于是抛出空指针;</li>
 *   <li>它只接得住断言异常:步骤里抛出的其它异常(空指针、越界)一路穿出服务器的 tick,服务器当场停下,其余用例的
 *       结果一个都看不到。</li>
 * </ul>
 * 这里两条都改掉:一步失败,后面的步骤一个都不跑;任何一步抛出的异常都只让这一条用例失败,原因带着那个异常。
 *
 * <h2>和原版一样的地方</h2>
 * 等待的一步每刻重试,断言不成立就下一刻再看,超时报最后一次的原因;一刻之内成立了的步骤接着往下走。
 * 在原版上只挂一条序列:先跑完这里的步骤,再报这里记下的失败。
 */
final class Steps {

    /** 一步:跑一次它的动作;{@code waits} 为真时断言不成立算"还没到",下一刻再跑。 */
    private record Step(String kind, Runnable body, boolean waits) {}

    private final GameTestHelper helper;
    private final List<Step> steps = new ArrayList<>();
    private int next;
    /** 第一处失败;记下之后后面的步骤不再跑。 */
    private GameTestAssertException failure;
    /** 正在空等的那一步从哪一刻开始等;不在空等是 -1。 */
    private long idleFrom = -1;

    Steps(GameTestHelper helper) {
        this.helper = helper;
        helper.startSequence()
                .thenWaitUntil(this::advance)
                .thenExecute(this::report);
    }

    /** 等到 {@code check} 不再抛断言异常。 */
    Steps thenWaitUntil(Runnable check) {
        steps.add(new Step("wait", check, true));
        return this;
    }

    /** 做一件事;它的断言不成立,这条用例就失败,后面的步骤不再跑。 */
    Steps thenExecute(Runnable action) {
        steps.add(new Step("execute", action, false));
        return this;
    }

    /** 空等 {@code ticks} 刻。 */
    Steps thenIdle(int ticks) {
        steps.add(new Step("idle", () -> {
            long now = helper.getTick();
            if (idleFrom < 0) {
                idleFrom = now;
            }
            if (now < idleFrom + ticks) {
                throw new GameTestAssertException("still idling");
            }
            idleFrom = -1;
        }, true));
        return this;
    }

    /** 空等 {@code ticks} 刻,再做一件事。 */
    Steps thenExecuteAfter(int ticks, Runnable action) {
        return thenIdle(ticks).thenExecute(action);
    }

    /** 前面的步骤都过了,这条用例通过。 */
    void thenSucceed() {
        steps.add(new Step("succeed", helper::succeed, false));
    }

    /**
     * 挂在原版序列上的那一步:从停下的地方接着跑。等待的一步断言不成立就原样抛出,原版下一刻再调;别的一切——做事的一步
     * 断言不成立、任何一步抛出别的异常——记成失败并停下,正常返回,交给 {@link #report}。
     */
    private void advance() {
        while (failure == null && next < steps.size()) {
            Step step = steps.get(next);
            try {
                step.body().run();
            } catch (GameTestAssertException notYet) {
                if (step.waits()) {
                    throw notYet;
                }
                failure = notYet;
                return;
            } catch (RuntimeException | AssertionError thrown) {
                Constants.LOG.error("[numen-gametest] step {} ({}) threw", next + 1, step.kind(), thrown);
                GameTestAssertException failed = new GameTestAssertException("step " + (next + 1) + " (" + step.kind()
                        + ") threw " + thrown);
                failed.initCause(thrown);
                failure = failed;
                return;
            }
            next++;
        }
    }

    /** 记下的失败交给原版:它把这条用例记为失败并收场。 */
    private void report() {
        if (failure != null) {
            throw failure;
        }
    }
}
