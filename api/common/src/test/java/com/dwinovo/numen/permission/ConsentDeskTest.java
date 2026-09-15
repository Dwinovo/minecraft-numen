package com.dwinovo.numen.permission;

import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 征询登记处:发起即推给主人、答复记授权、允许并记住写规则、附言只随拒绝、超时与主人不在按拒绝、新的顶掉旧的、
 * 任务收尾清授权撤请求、撤回说清为什么;清单一堆两种说法。时钟、主人在不在、推送撤回、主人的规则表都经假接线,
 * 不起服务器。
 */
class ConsentDeskTest {

    /** 假接线:手拨的时钟与主人在线开关,记下推过的请求、每次撤回的原因与写进表的规则。 */
    private static final class FakeLine implements ConsentDesk.Line {
        long now = 1000;
        boolean ownerOnline = true;
        final List<ConsentRequest> shown = new ArrayList<>();
        final List<Rule> remembered = new ArrayList<>();
        final List<String> clears = new ArrayList<>();
        int cleared;

        @Override public long gameTime() { return now; }
        @Override public boolean ownerPresent() { return ownerOnline; }
        @Override public void show(ConsentRequest request) { shown.add(request); }
        @Override public void clear(String why) { cleared++; clears.add(why); }
        @Override public void remember(List<Rule> allow) { remembered.addAll(allow); }
    }

    private static final class Task extends TaskRecord {
        Task() {
            super("mine", "call", Long.MAX_VALUE / 4);
        }
    }

    /** 记住的那一行只用种类项:解析信号名要引导 MC,这里不起。 */
    private static final Rule REMEMBER_LOGS = Rule.parse("break(minecraft:oak_log)");

    private static final Component LOG_NAME = Component.translatable("block.minecraft.oak_log");
    private static final Component PLACED = Component.translatable("numen.permission.signal.placed");

    private static ConsentItem log(int x) {
        return new ConsentItem(Action.Kind.BREAK, new BlockPos(x, 64, 0), ConsentItem.NO_ENTITY, "oak_log", LOG_NAME,
                "break(placed)", "placed by a player", PLACED, false, REMEMBER_LOGS);
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

        assertTrue(desk.answer(ticket.request().id(), ConsentAnswer.Decision.ALLOW_ONCE, ""));
        ConsentAnswer answer = ticket.poll();
        assertTrue(answer.allowed());
        assertEquals("", answer.words(), "允许没有理由可说");
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
    }

    @Test
    void aNoteOnlyGoesWithADeny() {
        ConsentDesk.Ticket ticket = desk.ask(new Task(), List.of(log(1)));
        for (ConsentAnswer.Decision allow : List.of(ConsentAnswer.Decision.ALLOW_ONCE,
                ConsentAnswer.Decision.ALLOW_REMEMBER)) {
            assertThrows(IllegalArgumentException.class, () -> desk.answer(ticket.request().id(), allow, "小心点"),
                    "主人要说点什么就是不让她照原样做,允许不带附言");
        }
        assertNull(ticket.poll(), "带附言的允许不算答复,请求还挂着");
        assertSame(ticket.request(), desk.pending());
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
        Component rex = Component.literal("Rex");
        Component owned = Component.translatable("numen.permission.signal.owned");
        Rule rememberRex = Rule.parse("attack(entity:00000000-0000-0000-0000-00000000002a)");
        items.add(new ConsentItem(Action.Kind.ATTACK, null, 42, "wolf", rex, "attack(owned)", "has an owner", owned,
                true, rememberRex));
        items.add(new ConsentItem(Action.Kind.ATTACK, null, 43, "wolf", Component.literal("Fang"), "attack(owned)",
                "has an owner", owned, true, rememberRex));
        List<ConsentItem.Group> groups = ConsentItem.listing(items);
        assertEquals(3, groups.size(), "两只起了不同名字的狼各是一堆");
        assertEquals("break 8 oak_log (0,64,0; 1,64,0; 2,64,0; 3,64,0; 4,64,0; 5,64,0; +2 more): placed by a player",
                groups.get(0).text());
        assertEquals(Component.translatable("numen.consent.line.break",
                        Component.translatable("numen.consent.count", LOG_NAME, "8"), PLACED), groups.get(0).shown(),
                "给主人看的是可翻译的名字、数量与理由,不带坐标");
        assertFalse(groups.get(0).irreversible());
        assertEquals("attack 1 wolf: has an owner", groups.get(1).text(), "实体只点名是哪一种,不报它此刻站在哪");
        assertEquals(Component.translatable("numen.consent.line.attack", rex, owned), groups.get(1).shown(),
                "一只就不写数量;起了名字的叫它的名字");
        assertTrue(groups.get(1).irreversible(), "撤不回的那一堆带着标记给答复框");
    }
}
