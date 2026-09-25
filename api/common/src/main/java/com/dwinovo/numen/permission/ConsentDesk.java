package com.dwinovo.numen.permission;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.platform.Services;

import net.minecraft.server.MinecraftServer;
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
 * 请求与撤回都推给主人的客户端({@link ConsentRequestPayload}),撤回带着为什么撤({@link Withdrawal});答复经
 * {@link #answer} 回来。发起者每刻读自己那张 {@link Ticket},模型不参与。
 *
 * <h2>附言</h2>
 * 附言只随拒绝:主人要她换个做法才会说,那句话就是拒绝的理由,随发起的任务收场送达。允许不带附言。
 *
 * <h2>任务期授权</h2>
 * 主人允许的清单记在发起它的那一方名下(作用域:任务记录,或一次等着执行的指令),{@link #granted} 是全部在册授权的
 * 快照,作为 {@link Gate} 的输入:被覆盖的 ask 放行。发起的那一方收尾(槽放开任务记录、指令收场)时 {@link #release}
 * 清掉它名下的授权与没答复的请求——各任务不各存一份。
 *
 * <h2>记住</h2>
 * 主人选"允许并记住"时,除了记成本任务的授权,清单每一条的 {@link ConsentItem#remember} 写进主人的
 * allow 表({@link PermissionStore#remember}),往后同类的事由主人层直接放行,不再问。
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

    /**
     * 一条征询没等到主人在答复框里答就撤掉了,为什么。这是给主人看的那一句:主人的客户端按自己的语言显示
     * ({@link #key},中英文案都在语言文件里),服务端只说是哪一种。撤掉的人说出真实的原因——发起的任务收场了,
     * 还是等着的那条指令被主人叫停、她离开了世界、她死了——不由登记处猜。
     *
     * <p>模型读到的是另一件事:它只在自己的调用被拒时读理由({@link ConsentAnswer#words}),那是
     * {@link #OWNER_ABSENT}、{@link #SUPERSEDED} 这几句;撤回的请求没有发起者再等它的结论。
     */
    public enum Withdrawal {
        /** 主人离线,或到点没答复。 */
        OWNER_ABSENT,
        /** 她又发起了一条,顶掉了这条。 */
        SUPERSEDED,
        /** 发起者要做的事此刻已经不用问了。 */
        UNNEEDED,
        /** 发起它的任务收场了(干完、失败、超时、被新的活顶掉、她自己叫停)。 */
        TASK_ENDED,
        /** 主人按了停止:发起它的任务或指令一并叫停。 */
        OWNER_STOPPED,
        /** 她离开了世界(休眠、遣散)。 */
        BODY_LEFT,
        /** 她死了。 */
        DIED;

        /** 给主人看的文案在语言文件里的键。 */
        public String key() {
            return "numen.consent.gone." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** {@link #reply} 的结局。 */
    public enum Reply {
        /** 答的是挂着的那一条,已经收下。 */
        ANSWERED,
        /** 答复的人不是这只同伴的主人。 */
        NOT_OWNER,
        /** 这个号没有挂着(已答复、已超时、被顶替,或者本来就没有)。 */
        NOT_PENDING
    }

    /** 登记处与外界的接线:时钟、主人在不在、把请求推给主人或撤回、把记住的规则写进主人的表。 */
    interface Line {
        long gameTime();

        boolean ownerPresent();

        void show(ConsentRequest request);

        /** 撤掉主人那边挂着的请求。{@code why} 是撤回的原因;主人自己答复的撤回为 null。 */
        void clear(Withdrawal why);

        void remember(List<Rule> allow);
    }

    private static final AtomicLong IDS = new AtomicLong();

    private final UUID companion;
    private final Line line;
    private Ticket pending;
    /** 作用域 → 主人为它答应下来的清单。按身份索引:任务记录与挂着的指令都没有值语义。 */
    private final Map<Object, List<ConsentItem>> grants = new IdentityHashMap<>();
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
     * 发起一次征询。挂着的那条被顶替;主人此刻不在线则当场按拒绝收尾,不推给主人。
     *
     * @param scope 发起它的那一方(任务记录,或一次等着执行的指令)——授权记在它名下,它收尾时一并清掉
     * @param items 清单,不能为空
     */
    public Ticket ask(Object scope, List<ConsentItem> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a consent request needs at least one item");
        }
        Ticket ticket = new Ticket(scope, new ConsentRequest(IDS.incrementAndGet(), companion, items,
                line.gameTime() + TIMEOUT_TICKS));
        if (pending != null) {
            settle(pending, new ConsentAnswer(ConsentAnswer.Decision.DENY, SUPERSEDED));
        }
        if (!line.ownerPresent()) {
            ticket.answer = new ConsentAnswer(ConsentAnswer.Decision.DENY, OWNER_ABSENT);
            line.clear(Withdrawal.OWNER_ABSENT);
            Constants.LOG.info("[numen-consent] {} ask #{} refused at once: owner offline", companion,
                    ticket.request.id());
            return ticket;
        }
        pending = ticket;
        line.show(ticket.request);
        Constants.LOG.info("[numen-consent] {} ask #{}: {}", companion, ticket.request.id(),
                ConsentItem.listingText(items));
        return ticket;
    }

    /**
     * 答复从外面进来的唯一入口:答复框的网络载荷与 {@code /numen consent} 命令都落这里。只认主人;认下的交给
     * 这只同伴的登记处 {@link #answer}。
     */
    public static Reply reply(ServerPlayer from, NumenPlayer companion, long requestId,
                             ConsentAnswer.Decision decision, String note) {
        if (!companion.isOwnedByPlayer(from.getUUID())) {
            return Reply.NOT_OWNER;
        }
        return of(companion).answer(requestId, decision, note) ? Reply.ANSWERED : Reply.NOT_PENDING;
    }

    /** 挂着这个请求号的在场同伴;没有是 null。请求号全服唯一,命令只带号不带同伴。 */
    public static NumenPlayer pendingAt(MinecraftServer server, long requestId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion) {
                ConsentRequest pending = of(companion).pending();
                if (pending != null && pending.id() == requestId) {
                    return companion;
                }
            }
        }
        return null;
    }

    /**
     * 这只同伴的主人的答复(经 {@link #reply} 进来;测试直接调)。允许就把清单记进发起任务的授权,允许并
     * 记住再把记住的规则写进主人的 allow 表;拒绝的附言就是理由,没有附言时理由是 {@link #OWNER_SAID_NO}。
     *
     * @param note 附言;只有拒绝带
     * @return 答的是不是挂着的那一条(过期、被顶替的号一律忽略)
     * @throws IllegalArgumentException 允许的答复带了附言
     */
    public boolean answer(long requestId, ConsentAnswer.Decision decision, String note) {
        String said = note == null ? "" : note.strip();
        if (decision != ConsentAnswer.Decision.DENY && !said.isEmpty()) {
            throw new IllegalArgumentException("a note only goes with a deny");
        }
        if (pending == null || pending.request.id() != requestId) {
            return false;
        }
        Ticket ticket = pending;
        ConsentAnswer answer;
        if (decision == ConsentAnswer.Decision.DENY) {
            answer = new ConsentAnswer(decision, said.isEmpty() ? OWNER_SAID_NO : said);
        } else {
            grants.computeIfAbsent(ticket.scope, k -> new ArrayList<>()).addAll(ticket.request.items());
            rebuildGranted();
            if (decision == ConsentAnswer.Decision.ALLOW_REMEMBER) {
                line.remember(ConsentItem.remembered(ticket.request.items()));
            }
            answer = new ConsentAnswer(decision, "");
        }
        settle(ticket, answer);
        line.clear(null);
        Constants.LOG.info("[numen-consent] {} #{} answered {}{}", companion, requestId, decision,
                said.isEmpty() ? "" : ": " + said);
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
            line.clear(Withdrawal.OWNER_ABSENT);
        }
    }

    /** 发起者不再需要这条答复(要做的事此刻已经不用问了):挂着的就撤回。 */
    public void withdraw(Ticket ticket) {
        if (pending == ticket) {
            withdrawPending(Withdrawal.UNNEEDED);
        }
    }

    /**
     * 发起的那一方收尾:清掉它名下的授权,撤回它没等到答复的请求。
     *
     * @param why 它挂着的请求因何撤回,主人看到的就是这一句——由收尾的一方按真实原因给
     */
    public void release(Object scope, Withdrawal why) {
        if (grants.remove(scope) != null) {
            rebuildGranted();
        }
        if (pending != null && pending.scope == scope) {
            withdrawPending(why);
        }
    }

    /** 挂着的那条撤掉:按拒绝收尾(发起者已经不等它了,理由留空),主人那边收起并看到为什么。 */
    private void withdrawPending(Withdrawal why) {
        settle(pending, new ConsentAnswer(ConsentAnswer.Decision.DENY, ""));
        line.clear(why);
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
        private final Object scope;
        private final ConsentRequest request;
        private ConsentAnswer answer;

        private Ticket(Object scope, ConsentRequest request) {
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

    /** 真身体的接线:游戏刻、主人在线与否、载荷推给主人、记住的规则进主人的存档。 */
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
        public void clear(Withdrawal why) {
            ServerPlayer owner = body.resolveOwnerPlayer();
            if (owner != null) {
                Services.NETWORK.sendToPlayer(owner, ConsentRequestPayload.none(body.getUUID(), why));
            }
        }

        @Override
        public void remember(List<Rule> allow) {
            PermissionStore.of(body.getServer(), body.getOwnerUuid()).remember(allow);
        }

    }
}
