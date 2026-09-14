package com.dwinovo.numen.permission;

import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 征询登记处:发起即推卡、答复记授权、超时与主人不在按拒绝、新的顶掉旧的、任务收尾清授权撤请求。
 * 时钟、主人在不在、推卡撤卡都经假接线,不起服务器。
 */
class ConsentDeskTest {

    /** 假接线:手拨的时钟与主人在线开关,记下推过的卡与撤卡次数。 */
    private static final class FakeLine implements ConsentDesk.Line {
        long now = 1000;
        boolean ownerOnline = true;
        final List<ConsentRequest> shown = new ArrayList<>();
        int cleared;

        @Override public long gameTime() { return now; }
        @Override public boolean ownerPresent() { return ownerOnline; }
        @Override public void show(ConsentRequest request) { shown.add(request); }
        @Override public void clear() { cleared++; }
    }

    private static final class Task extends TaskRecord {
        Task() {
            super("mine", "call", Long.MAX_VALUE / 4);
        }
    }

    private static ConsentItem log(int x) {
        return new ConsentItem(Action.Kind.BREAK, new BlockPos(x, 64, 0), ConsentItem.NO_ENTITY, "oak_log",
                "break(placed)", "placed by a player", false);
    }

    private FakeLine line;
    private ConsentDesk desk;

    @BeforeEach
    void setUp() {
        line = new FakeLine();
        desk = new ConsentDesk(UUID.randomUUID(), line);
    }

    @Test
    void askPushesTheCardAndAnAllowBecomesATaskGrant() {
        Task task = new Task();
        ConsentDesk.Ticket ticket = desk.ask(task, List.of(log(1), log(2)), "挖 oak_log 0/2");

        assertEquals(1, line.shown.size(), "发起就推给主人");
        assertSame(ticket.request(), desk.pending());
        assertEquals(line.now + ConsentDesk.TIMEOUT_TICKS, ticket.request().expiresAtGameTime());
        assertNull(ticket.poll(), "主人还没按");
        assertTrue(desk.granted().isEmpty());

        assertTrue(desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "  小心点  "));
        ConsentAnswer answer = ticket.poll();
        assertTrue(answer.allowed());
        assertEquals("小心点", answer.words(), "附言原话,去掉首尾空白");
        assertEquals(List.of(log(1), log(2)), desk.granted(), "答应的清单记成任务期授权");
        assertNull(desk.pending());
        assertEquals(1, line.cleared, "答复之后撤卡");
        assertFalse(desk.answer(ticket.request().id(), ConsentAnswer.Decision.DENY, ""), "同一个号不能答两次");
    }

    @Test
    void rememberIsATaskGrantUntilRulesExist() {
        Task task = new Task();
        ConsentDesk.Ticket ticket = desk.ask(task, List.of(log(1)), "r");
        desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_REMEMBER, "");
        assertTrue(ticket.poll().allowed());
        assertEquals(List.of(log(1)), desk.granted());
        assertTrue(ticket.poll().allowance(List.of(log(1))).contains("记住规则在下一步"));
    }

    @Test
    void aDenyCarriesTheOwnersWordsOrSaysTheOwnerRefused() {
        Task task = new Task();
        ConsentDesk.Ticket bare = desk.ask(task, List.of(log(1)), "r");
        desk.answer(bare.request().id(), ConsentAnswer.Decision.DENY, " ");
        assertFalse(bare.poll().allowed());
        assertEquals(ConsentDesk.OWNER_SAID_NO, bare.poll().words());

        ConsentDesk.Ticket worded = desk.ask(task, List.of(log(1)), "r");
        desk.answer(worded.request().id(), ConsentAnswer.Decision.DENY, "那是我的柱子");
        assertEquals("那是我的柱子", worded.poll().words());
        assertTrue(worded.poll().refusal(List.of(log(1))).contains("那是我的柱子"));
        assertTrue(desk.granted().isEmpty(), "拒绝不记授权");
    }

    @Test
    void anUnansweredRequestExpiresOnGameTicksAsDenied() {
        ConsentDesk.Ticket ticket = desk.ask(new Task(), List.of(log(1)), "r");
        line.now = ticket.request().expiresAtGameTime() - 1;
        desk.tick();
        assertNull(ticket.poll(), "还没到点");

        line.now = ticket.request().expiresAtGameTime();
        desk.tick();
        assertFalse(ticket.poll().allowed());
        assertEquals(ConsentDesk.OWNER_ABSENT, ticket.poll().words());
        assertNull(desk.pending());
        assertFalse(desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, ""), "过期的号不认");
    }

    @Test
    void anAbsentOwnerMeansDeniedAtOnceOrAsSoonAsHeLeaves() {
        line.ownerOnline = false;
        ConsentDesk.Ticket offline = desk.ask(new Task(), List.of(log(1)), "r");
        assertEquals(ConsentDesk.OWNER_ABSENT, offline.poll().words(), "主人不在线:当场按拒绝");
        assertTrue(line.shown.isEmpty(), "不推卡");

        line.ownerOnline = true;
        ConsentDesk.Ticket waiting = desk.ask(new Task(), List.of(log(1)), "r");
        line.ownerOnline = false;
        desk.tick();
        assertEquals(ConsentDesk.OWNER_ABSENT, waiting.poll().words(), "挂着的时候主人下线");
    }

    @Test
    void aNewRequestSupersedesTheOldOne() {
        Task task = new Task();
        ConsentDesk.Ticket first = desk.ask(task, List.of(log(1)), "first");
        ConsentDesk.Ticket second = desk.ask(task, List.of(log(2)), "second");

        assertFalse(first.poll().allowed());
        assertEquals(ConsentDesk.SUPERSEDED, first.poll().words());
        assertSame(second.request(), desk.pending());
        assertEquals(2, line.shown.size(), "新的那张推过去顶掉旧卡");
        assertFalse(desk.answer(first.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, ""));
        assertTrue(desk.granted().isEmpty(), "答旧号不记授权");
    }

    @Test
    void releasingATaskDropsItsGrantsAndWithdrawsItsRequest() {
        Task done = new Task();
        Task other = new Task();
        ConsentDesk.Ticket granted = desk.ask(done, List.of(log(1)), "r");
        desk.answer(granted.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
        ConsentDesk.Ticket otherGrant = desk.ask(other, List.of(log(9)), "r");
        desk.answer(otherGrant.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
        ConsentDesk.Ticket open = desk.ask(done, List.of(log(2)), "r");

        desk.release(done);

        assertEquals(List.of(log(9)), desk.granted(), "只清收尾那个任务名下的");
        assertEquals(ConsentDesk.TASK_ENDED, open.poll().words(), "它没等到的请求撤回");
        assertNull(desk.pending());
    }

    @Test
    void withdrawingAnswersNothingAndClearsTheCard() {
        ConsentDesk.Ticket ticket = desk.ask(new Task(), List.of(log(1)), "r");
        int before = line.cleared;
        desk.withdraw(ticket);
        assertNull(desk.pending());
        assertEquals(before + 1, line.cleared);
        assertFalse(ticket.poll().allowed());
        assertTrue(desk.granted().isEmpty());
    }

    @Test
    void theListingGroupsByVerbKindAndCauseAndNamesSixCells() {
        List<ConsentItem> items = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            items.add(log(x));
        }
        items.add(new ConsentItem(Action.Kind.ATTACK, new BlockPos(5, 64, 5), 42, "wolf", "attack(owned)",
                "has an owner", true));
        List<ConsentItem.Line> lines = ConsentItem.listing(items);
        assertEquals(2, lines.size());
        assertEquals("break 8 oak_log (0,64,0; 1,64,0; 2,64,0; 3,64,0; 4,64,0; 5,64,0; +2 more): placed by a player",
                lines.get(0).text());
        assertFalse(lines.get(0).irreversible());
        assertEquals("attack 1 wolf (5,64,5): has an owner", lines.get(1).text());
        assertTrue(lines.get(1).irreversible(), "撤不回的那一行带着标记给卡片");
    }
}
