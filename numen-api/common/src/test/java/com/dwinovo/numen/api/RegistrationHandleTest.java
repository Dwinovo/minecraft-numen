package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskFactory;
import com.dwinovo.numen.api.task.TaskRecord;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 句柄只撤自己登记的那一条:表里同一个键后来被别的登记占了,旧句柄(哪怕第二次 close)不动它。
 * 两张表各一种行为:{@link TaskFactory} 同一种活后登记的顶掉先登记的;{@link EventTypes} 拒绝第二次登记同一个 id,
 * 先登记的撤掉之后别人才能登记。
 */
class RegistrationHandleTest {

    /** 一种活的记录,只为当 {@link TaskFactory} 的键。 */
    private static final class KeyedRecord extends TaskRecord<Void> {
        KeyedRecord() {
            super("gt", "gt", 0L);
        }
    }

    /** 认得出是哪个执行器造的。 */
    private record Named(String name) implements Task {
        @Override
        public TaskState tick(NumenPlayer companion) {
            return TaskState.SUCCESS;
        }

        @Override
        public void stop(NumenPlayer companion, StopReason why) {
        }

        @Override
        public TaskResult result(TaskState terminal) {
            return TaskResult.ok(name);
        }
    }

    /** 这一种活此刻用的是哪个执行器;没登记着是 {@code unsupported}。 */
    private static String runner() {
        String name = TaskFactory.create(null, new KeyedRecord()).name();
        return name.equals("first") || name.equals("second") ? name : "unsupported";
    }

    @Test
    void aReplacedRunnersHandleLeavesTheReplacementAlone() {
        Handle first = TaskFactory.register(KeyedRecord.class, (body, record) -> new Named("first"));
        Handle second = TaskFactory.register(KeyedRecord.class, (body, record) -> new Named("second"));
        assertEquals("second", runner(), "the later registration takes the type over");

        first.close();
        first.close();
        assertEquals("second", runner(), "the replaced registration's handle took out the one that replaced it");

        second.close();
        assertEquals("unsupported", runner());
    }

    @Test
    void anEventTypeClosedAndRegisteredAgainSurvivesTheOldHandleClosingAgain() {
        Handle first = EventTypes.register(EventTypes.event("gt_handle_event", false));
        first.close();
        Handle second = EventTypes.register(EventTypes.event("gt_handle_event", false));

        first.close();

        assertTrue(EventTypes.isRegistered("gt_handle_event"),
                "an equal-looking row registered later was taken out by the earlier handle");
        second.close();
        assertFalse(EventTypes.isRegistered("gt_handle_event"));
    }
}
