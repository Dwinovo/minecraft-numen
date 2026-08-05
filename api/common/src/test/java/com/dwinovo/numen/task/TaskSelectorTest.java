package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 谁拿身体。
 *
 * <p>这一层取代了从前"每条链出一个浮点价、挑最大的"那套。浮点的问题不是不准,
 * 是<b>没人调得准</b>:加一条链就要重新掂量它跟其余所有链的相对大小,而那些数
 * 表达的其实是一个固定的顺序。所以这套测试的重点是<b>顺序真的固定</b>——
 * 层与层之间谁压谁,不随任何状态变。
 */
class TaskSelectorTest {

    /** 一个只回答"能不能跑"的桩;不碰 Minecraft,所以 companion 传 null 就行。 */
    private static final class Stub implements Task {
        private final String id;
        private boolean ready;

        Stub(String id, boolean ready) {
            this.id = id;
            this.ready = ready;
        }

        @Override public boolean canRun(NumenPlayer companion) {
            return ready;
        }

        @Override public TaskState tick(NumenPlayer companion) {
            return TaskState.RUNNING;
        }

        @Override public void stop(NumenPlayer companion, StopReason why) {
        }

        @Override public String name() {
            return id;
        }
    }

    private static Task pick(List<Task> reflexes, Task sync, Task current, List<Task> idle) {
        return TaskSelector.select(reflexes, sync, current, idle, null);
    }

    // ---- 层与层 ----

    @Test
    void reflexBeatsEverything() {
        // 等模型就晚了 —— 快淹死的时候在挖矿也得撒手
        Stub reflex = new Stub("反射", true);
        assertSame(reflex, pick(List.of(reflex), new Stub("同步", true),
                new Stub("当前", true), List.of(new Stub("姿态", true))));
    }

    @Test
    void syncBeatsTheCurrentTask() {
        // 同步动作有人挂着等(回合冻着),而队首的长活可能几分钟——
        // 让它排在后面等于把整个对话卡到 deadline
        Stub sync = new Stub("同步", true);
        assertSame(sync, pick(List.of(new Stub("反射", false)), sync,
                new Stub("当前", true), List.of(new Stub("姿态", true))));
    }

    @Test
    void currentTaskBeatsIdlePose() {
        // 她挖着矿跟你说话不该停下回头 —— 这条关系旧调度里就有
        // (SpeakingLookChain 的出价本来就低于任务基准价),不是新决定
        Stub current = new Stub("当前", true);
        assertSame(current, pick(List.of(), null, current, List.of(new Stub("姿态", true))));
    }

    @Test
    void idlePoseOnlyWhenNothingElseWants() {
        Stub pose = new Stub("姿态", true);
        assertSame(pose, pick(List.of(new Stub("反射", false)), null, null, List.of(pose)));
    }

    @Test
    void nobodyWantsTheBodyMeansSheJustStands() {
        // 空闲是雕塑 —— 这不是缺陷,是没有事情要做
        assertNull(pick(List.of(new Stub("反射", false)), null, null, List.of(new Stub("姿态", false))));
        assertNull(pick(null, null, null, null));
    }

    // ---- 层内 ----

    @Test
    void reflexesGoByRegistrationOrder() {
        // 摔落缓冲注册号 10、脱困 50:同时触发时先问摔落。
        // 这个顺序照搬旧的浮点大小(MLG 10.0 > UNSTUCK 2.0),否则她从高处
        // 掉下来会先去脱困而不是接水 —— 那是这次搬家最容易静默改坏的地方。
        Stub mlg = new Stub("摔落", true);
        Stub unstuck = new Stub("脱困", true);
        assertSame(mlg, pick(List.of(mlg, unstuck), null, null, null));
        assertSame(unstuck, pick(List.of(unstuck, mlg), null, null, null), "顺序反过来答案就该反过来");
    }

    @Test
    void aDormantReflexIsSkippedNotBlocking() {
        // 休眠的反射不挡后面的 —— 不饿的时候进食反射不该把身体扣住
        Stub sleeping = new Stub("休眠的", false);
        Stub awake = new Stub("醒着的", true);
        assertSame(awake, pick(List.of(sleeping, awake), null, null, null));
    }

    @Test
    void idlePosesAlsoGoByOrder() {
        Stub first = new Stub("一", true);
        Stub second = new Stub("二", true);
        assertSame(first, pick(List.of(), null, null, List.of(first, second)));
    }

    // ---- 休眠 ----

    @Test
    void aStandingTaskThatCannotRunYieldsWithoutDying() {
        // 常驻任务"暂时干不了"(主人走出 60 格、鱼竿没了)靠 canRun 表达:
        // 那不是失败也不是让位,是休眠——身体让给别人,条件回来了自己接着干
        Stub fishing = new Stub("钓鱼", false);
        Stub pose = new Stub("姿态", true);
        assertSame(pose, pick(List.of(), null, fishing, List.of(pose)));

        fishing.ready = true;
        assertSame(fishing, pick(List.of(), null, fishing, List.of(pose)), "条件回来它就该拿回身体");
    }

    @Test
    void selectionIsPureAndRepeatable() {
        // 只读状态、不改状态:同一批输入问几次都是同一个答案。
        // 上层因为协议原因这一刻跑不成,下一刻再问就是了。
        List<Task> reflexes = List.of(new Stub("反射", false));
        Stub current = new Stub("当前", true);
        Task first = pick(reflexes, null, current, null);
        assertTrue(first == pick(reflexes, null, current, null));
        assertTrue(first == pick(reflexes, null, current, null));
    }
}
