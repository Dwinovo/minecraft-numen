package com.dwinovo.numen.permission;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一只同伴的征询登记处:发起、答复、超时,和答应下来的任务期授权。
 *
 * <p>挂在身体上({@link NumenPlayer#state}):身体没了登记处跟着没,关服时没有一张静态表需要清。
 *
 * <h2>征询</h2>
 * 同一只同伴同时只挂一条;新的顶掉旧的,旧的按拒绝收尾({@link #SUPERSEDED})。发起那一刻主人
 * 不在线,或者挂着的时候主人下线、到了 {@link #TIMEOUT_TICKS},都按拒绝收尾({@link #OWNER_ABSENT})。
 * 请求与撤回都推给主人的客户端({@link ConsentRequestPayload}),答复经 {@link #answer} 回来。
 * 发起者每刻读自己那张 {@link Ticket},模型不参与。
 *
 * <h2>任务期授权</h2>
 * 主人允许的清单记在发起它的任务记录名下,{@link #granted} 是全部在册授权的快照,作为
 * {@link Gate} 的输入:被覆盖的 ask 放行。任务收尾(槽放开这条记录)时 {@link #release} 清掉它名下的
 * 授权与没答复的请求——各任务不各存一份。
 */
public final class ConsentDesk {

    /** 等主人答复多久(游戏刻)。 */
    public static final long TIMEOUT_TICKS = 2 * 60 * 20;

    /** 主人按了拒绝、没有附言时回执里的理由。 */
    public static final String OWNER_SAID_NO = "主人拒绝";
    /** 主人离线或超时没答复。 */
    public static final String OWNER_ABSENT = "主人不在场,无法征得同意";
    /** 同一只同伴又发起了一条。 */
    public static final String SUPERSEDED = "被新的请求顶替";
    /** 发起的任务先结束了(干完、被换、叫停、身体离开)。 */
    public static final String TASK_ENDED = "发起征询的任务已经结束";
    /** 发起者撤回了(要做的事已经不用问)。 */
    public static final String WITHDRAWN = "发起者撤回了这条征询";

    /** 登记处与外界的接线:时钟、主人在不在、把请求推给主人或撤回。 */
    interface Line {
        long gameTime();

        boolean ownerPresent();

        void show(ConsentRequest request);

        void clear();
    }

    private static final AtomicLong IDS = new AtomicLong();

    private final UUID companion;
    private final Line line;
    private Ticket pending;
    /** 任务记录 → 主人为它答应下来的清单。按记录身份索引:记录没有值语义。 */
    private final Map<TaskRecord, List<ConsentItem>> grants = new IdentityHashMap<>();
    /** {@link #grants} 的扁平快照,变了才重建;交给裁决快照,任何线程只读。 */
    private List<ConsentItem> granted = List.of();

    ConsentDesk(UUID companion, Line line) {
        this.companion = companion;
        this.line = line;
    }

    /** 这具身体的登记处(首次取时建)。主线程。 */
    public static ConsentDesk of(NumenPlayer companion) {
        return companion.state(ConsentDesk.class, () -> new ConsentDesk(companion.getUUID(), new BodyLine(companion)));
    }

    /**
     * 发起一次征询。挂着的那条被顶替;主人此刻不在线则当场按拒绝收尾,不推卡片。
     *
     * @param scope  发起它的任务记录——授权记在它名下,它收尾时一并清掉
     * @param items  清单,不能为空
     * @param reason 给主人看的原因
     */
    public Ticket ask(TaskRecord scope, List<ConsentItem> items, String reason) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a consent request needs at least one item");
        }
        Ticket ticket = new Ticket(scope, new ConsentRequest(IDS.incrementAndGet(), companion, items, reason,
                line.gameTime() + TIMEOUT_TICKS));
        if (pending != null) {
            settle(pending, new ConsentAnswer(ConsentAnswer.Decision.DENY, SUPERSEDED));
        }
        if (!line.ownerPresent()) {
            ticket.answer = new ConsentAnswer(ConsentAnswer.Decision.DENY, OWNER_ABSENT);
            line.clear();
            Constants.LOG.info("[numen-consent] {} ask #{} refused at once: owner offline", companion,
                    ticket.request.id());
            return ticket;
        }
        pending = ticket;
        line.show(ticket.request);
        Constants.LOG.info("[numen-consent] {} ask #{} ({} item(s)): {}", companion, ticket.request.id(),
                items.size(), reason);
        return ticket;
    }

    /**
     * 主人的答复(网络载荷与测试的落点)。允许就把清单记进发起任务的授权;拒绝没有附言时理由是
     * {@link #OWNER_SAID_NO}。
     *
     * @return 答的是不是挂着的那一条(过期、被顶替的号一律忽略)
     */
    public boolean answer(long requestId, ConsentAnswer.Decision decision, String note) {
        if (pending == null || pending.request.id() != requestId) {
            return false;
        }
        Ticket ticket = pending;
        String words = note == null ? "" : note.strip();
        if (decision == ConsentAnswer.Decision.DENY && words.isEmpty()) {
            words = OWNER_SAID_NO;
        }
        if (decision != ConsentAnswer.Decision.DENY) {
            grants.computeIfAbsent(ticket.scope, k -> new ArrayList<>()).addAll(ticket.request.items());
            rebuildGranted();
        }
        settle(ticket, new ConsentAnswer(decision, words));
        line.clear();
        Constants.LOG.info("[numen-consent] {} #{} answered {}{}", companion, requestId, decision,
                words.isEmpty() ? "" : ": " + words);
        return true;
    }

    /** 每服务端 tick 一次:主人下线或到点,挂着的那条按拒绝收尾。 */
    public void tick() {
        if (pending == null) {
            return;
        }
        if (!line.ownerPresent() || line.gameTime() >= pending.request.expiresAtGameTime()) {
            Constants.LOG.info("[numen-consent] {} #{} expired: {}", companion, pending.request.id(), OWNER_ABSENT);
            settle(pending, new ConsentAnswer(ConsentAnswer.Decision.DENY, OWNER_ABSENT));
            line.clear();
        }
    }

    /** 发起者不再需要这条答复(要做的事此刻已经不用问了):挂着的就撤回。 */
    public void withdraw(Ticket ticket) {
        if (pending == ticket) {
            settle(ticket, new ConsentAnswer(ConsentAnswer.Decision.DENY, WITHDRAWN));
            line.clear();
        }
    }

    /** 任务收尾:清掉它名下的授权,撤回它没等到答复的请求。 */
    public void release(TaskRecord scope) {
        if (grants.remove(scope) != null) {
            rebuildGranted();
        }
        if (pending != null && pending.scope == scope) {
            settle(pending, new ConsentAnswer(ConsentAnswer.Decision.DENY, TASK_ENDED));
            line.clear();
        }
    }

    /** 全部在册的任务期授权(不可变快照)。 */
    public List<ConsentItem> granted() {
        return granted;
    }

    /** 挂着的那条;没有是 null。 */
    public ConsentRequest pending() {
        return pending == null ? null : pending.request;
    }

    private void settle(Ticket ticket, ConsentAnswer answer) {
        ticket.answer = answer;
        if (pending == ticket) {
            pending = null;
        }
    }

    private void rebuildGranted() {
        List<ConsentItem> all = new ArrayList<>();
        grants.values().forEach(all::addAll);
        granted = List.copyOf(all);
    }

    /** 发起者手里的一张号:每刻 {@link #poll},有结论之前是 null。 */
    public static final class Ticket {
        private final TaskRecord scope;
        private final ConsentRequest request;
        private ConsentAnswer answer;

        private Ticket(TaskRecord scope, ConsentRequest request) {
            this.scope = scope;
            this.request = request;
        }

        public ConsentRequest request() {
            return request;
        }

        /** 结论;还在等主人是 null。 */
        public ConsentAnswer poll() {
            return answer;
        }
    }

    /** 真身体的接线:游戏刻、主人在线与否、载荷推给主人。 */
    private record BodyLine(NumenPlayer body) implements Line {
        @Override
        public long gameTime() {
            return body.level().getGameTime();
        }

        @Override
        public boolean ownerPresent() {
            return body.resolveOwnerPlayer() != null;
        }

        @Override
        public void show(ConsentRequest request) {
            ServerPlayer owner = body.resolveOwnerPlayer();
            if (owner != null) {
                Services.NETWORK.sendToPlayer(owner, ConsentRequestPayload.of(request));
            }
        }

        @Override
        public void clear() {
            ServerPlayer owner = body.resolveOwnerPlayer();
            if (owner != null) {
                Services.NETWORK.sendToPlayer(owner, ConsentRequestPayload.none(body.getUUID()));
            }
        }
    }
}
