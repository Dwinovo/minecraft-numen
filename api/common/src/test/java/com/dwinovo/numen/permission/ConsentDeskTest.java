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
 * 征询登记处:发起即推给主人、答复记授权、允许并记住写规则、允许的附言当场转交、超时与主人不在按拒绝、
 * 新的顶掉旧的、任务收尾清授权撤请求、撤回说清为什么。时钟、主人在不在、推送撤回、主人的规则表、转交附言都经
 * 假接线,不起服务器。
 */
class ConsentDeskTest {

    /** 假接线:手拨的时钟与主人在线开关,记下推过的请求、每次撤回的原因、写进表的规则与转交的附言。 */
    private static final class FakeLine implements ConsentDesk.Line {
        long now = 1000;
        boolean ownerOnline = true;
        final List<ConsentRequest> shown = new ArrayList<>();
        final List<Rule> remembered = new ArrayList<>();
        final List<String> clears = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int cleared;

        @Override public long gameTime() { return now; }
        @Override public boolean ownerPresent() { return ownerOnline; }
        @Override public void show(ConsentRequest request) { shown.add(request); }
        @Override public void clear(String why) { cleared++; clears.add(why); }
        @Override public void remember(List<Rule> allow) { remembered.addAll(allow); }
        @Override public void relayNote(ConsentAnswer.Decision decision, List<ConsentItem> asked, String note) {
            notes.add(decision + " " + ConsentItem.listingText(asked) + " | " + note);
        }
    }

    private static final class Task extends TaskRecord {
        Task() {
            super("mine", "call", Long.MAX_VALUE / 4);
        }
    }

    /** 记住的那一行只用种类项:解析信号名要引导 MC,这里不起。 */
    private static final Rule REMEMBER_LOGS = Rule.parse("break(minecraft:oak_log)");

    private static ConsentItem log(int x) {
        return new ConsentItem(Action.Kind.BREAK, new BlockPos(x, 64, 0), ConsentItem.NO_ENTITY, "oak_log",
                "break(placed)", "placed by a player", false, REMEMBER_LOGS);
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
        ConsentDesk.Ticket ticket = desk.ask(task, List.of(log(1), log(2)));

        assertEquals(1, line.shown.size(), "发起就推给主人");
        assertSame(ticket.request(), desk.pending());
        assertEquals(line.now + ConsentDesk.TIMEOUT_TICKS, ticket.request().expiresAtGameTime());
        assertNull(ticket.poll(), "主人还没按");
        assertTrue(desk.granted().isEmpty());

        assertTrue(desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "  小心点  "));
        ConsentAnswer answer = ticket.poll();
        assertTrue(answer.allowed());
        assertEquals("", answer.words(), "允许的附言不等任务收场的回执");
        assertEquals(List.of("ALLOW_ONCE break 2 oak_log (1,64,0; 2,64,0): placed by a player | 小心点"), line.notes,
                "附言原话当场转给她,带着答应的是什么");
        assertEquals(List.of(log(1), log(2)), desk.granted(), "答应的清单记成任务期授权");
        assertNull(desk.pending());
        assertEquals(List.of(""), line.clears, "主人自己答的,撤回不带原因");
        assertFalse(desk.answer(ticket.request().id(), ConsentAnswer.Decision.DENY, ""), "同一个号不能答两次");
    }

    @Test
    void rememberWritesTheRulesOnceAndStillGrantsTheTask() {
        Task task = new Task();
        ConsentDesk.Ticket ticket = desk.ask(task, List.of(log(1), log(2)));
        desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_REMEMBER, "");
        assertTrue(ticket.poll().allowed());
        assertEquals(List.of(log(1), log(2)), desk.granted(), "本任务里照样放行");
        assertEquals(List.of(REMEMBER_LOGS), line.remembered, "两条同一行规则,只写一次");
        String allowance = ticket.poll().allowance(List.of(log(1), log(2)));
        assertTrue(allowance.contains("allow break(minecraft:oak_log)"), "回执说记下了哪一行: " + allowance);
        assertEquals(List.of("allow break(minecraft:oak_log)"), ConsentItem.rememberedRows(List.of(log(1), log(2))),
                "主人在选项上看到的和回执里的是同一行");
        assertTrue(line.notes.isEmpty(), "没说话就没有附言可转");
    }

    @Test
    void allowOnceAndDenyRememberNothing() {
        Task task = new Task();
        ConsentDesk.Ticket once = desk.ask(task, List.of(log(1)));
        desk.answer(once.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
        ConsentDesk.Ticket no = desk.ask(task, List.of(log(2)));
        desk.answer(no.request().id(), ConsentAnswer.Decision.DENY, "");
        assertTrue(line.remembered.isEmpty());
        assertFalse(once.poll().allowance(List.of(log(1))).contains("remembered"));
    }

    @Test
    void aDenyCarriesTheOwnersWordsOrSaysTheOwnerRefused() {
        Task task = new Task();
        ConsentDesk.Ticket bare = desk.ask(task, List.of(log(1)));
        desk.answer(bare.request().id(), ConsentAnswer.Decision.DENY, " ");
        assertFalse(bare.poll().allowed());
        assertEquals(ConsentDesk.OWNER_SAID_NO, bare.poll().words());

        ConsentDesk.Ticket worded = desk.ask(task, List.of(log(1)));
        desk.answer(worded.request().id(), ConsentAnswer.Decision.DENY, "那是我的柱子");
        assertEquals("那是我的柱子", worded.poll().words());
        assertTrue(worded.poll().refusal(List.of(log(1))).contains("那是我的柱子"));
        assertTrue(line.notes.isEmpty(), "拒绝的附言就是拒绝的理由,随任务收场送达,不另转一份");
        assertTrue(desk.granted().isEmpty(), "拒绝不记授权");
    }

    @Test
    void aWithdrawnRequestSaysWhyItWentAway() {
        ConsentDesk.Ticket expired = desk.ask(new Task(), List.of(log(1)));
        line.now = expired.request().expiresAtGameTime();
        desk.tick();
        Task done = new Task();
        desk.ask(done, List.of(log(2)));
        desk.release(done);
        ConsentDesk.Ticket unneeded = desk.ask(new Task(), List.of(log(3)));
        desk.withdraw(unneeded);

        assertEquals(List.of(ConsentDesk.OWNER_ABSENT, ConsentDesk.TASK_ENDED, ConsentDesk.WITHDRAWN), line.clears,
                "没等到答复就撤的,主人那边看得到为什么");
    }

    @Test
    void anUnansweredRequestExpiresOnGameTicksAsDenied() {
        ConsentDesk.Ticket ticket = desk.ask(new Task(), List.of(log(1)));
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
        ConsentDesk.Ticket offline = desk.ask(new Task(), List.of(log(1)));
        assertEquals(ConsentDesk.OWNER_ABSENT, offline.poll().words(), "主人不在线:当场按拒绝");
        assertTrue(line.shown.isEmpty(), "不推卡");

        line.ownerOnline = true;
        ConsentDesk.Ticket waiting = desk.ask(new Task(), List.of(log(1)));
        line.ownerOnline = false;
        desk.tick();
        assertEquals(ConsentDesk.OWNER_ABSENT, waiting.poll().words(), "挂着的时候主人下线");
    }

    @Test
    void aNewRequestSupersedesTheOldOne() {
        Task task = new Task();
        ConsentDesk.Ticket first = desk.ask(task, List.of(log(1)));
        ConsentDesk.Ticket second = desk.ask(task, List.of(log(2)));

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
        ConsentDesk.Ticket granted = desk.ask(done, List.of(log(1)));
        desk.answer(granted.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
        ConsentDesk.Ticket otherGrant = desk.ask(other, List.of(log(9)));
        desk.answer(otherGrant.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
        ConsentDesk.Ticket open = desk.ask(done, List.of(log(2)));

        desk.release(done);

        assertEquals(List.of(log(9)), desk.granted(), "只清收尾那个任务名下的");
        assertEquals(ConsentDesk.TASK_ENDED, open.poll().words(), "它没等到的请求撤回");
        assertNull(desk.pending());
    }

    @Test
    void withdrawingAnswersNothingAndClearsTheCard() {
        ConsentDesk.Ticket ticket = desk.ask(new Task(), List.of(log(1)));
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
        items.add(new ConsentItem(Action.Kind.ATTACK, null, 42, "wolf", "attack(owned)",
                "has an owner", true, Rule.parse("attack(entity:00000000-0000-0000-0000-00000000002a)")));
        List<ConsentItem.Line> lines = ConsentItem.listing(items);
        assertEquals(2, lines.size());
        assertEquals("break 8 oak_log (0,64,0; 1,64,0; 2,64,0; 3,64,0; 4,64,0; 5,64,0; +2 more): placed by a player",
                lines.get(0).text());
        assertFalse(lines.get(0).irreversible());
        assertEquals("attack 1 wolf: has an owner", lines.get(1).text(), "实体只点名是哪一种,不报它此刻站在哪");
        assertTrue(lines.get(1).irreversible(), "撤不回的那一行带着标记给卡片");
    }
}
