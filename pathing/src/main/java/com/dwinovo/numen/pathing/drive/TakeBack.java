package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Footing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 事后撤掉路上垫下的方块。什么时候算"事后"由宿主定(一次导航到了,或一整件活干完),撤哪几块由它交进来——都是导航实际账里
 * 放下、之后没再挖掉的({@link EditLedger#placedBlocks()})。
 *
 * <p>后放的先撤,每一块都照原版挖:够得着、看得见就站着挖;不然走过去(子导航,只走不改——撤回是为了还原,不为它再改别处),
 * 走到够得着、看得见它的地方再挖,走不到就照实留下。正托着身体的那几块不挖——挖了她会掉下去;托着她的全是要撤的块时,
 * 先走到脚下不是它们的地方,走不开的照实留下。一块此刻已经不是她放下的那种方块了(被人换过、挖过),就不是她留下的东西,不撤也不算留下。
 *
 * <p>挖之前问许可这一格能不能挖,与规划问的是同一个端口;拒绝的、动手时被 {@link Effector} 拒的,照实留下并带着理由。
 * 挖掉的记进实际账,账与子导航共用一本。
 */
public final class TakeBack {

    /** 没撤掉的一块:在哪、是什么、为什么。 */
    public record Left(BlockPos pos, BlockState block, Why why) {}

    /** 为什么没撤掉。 */
    public sealed interface Why {
        /** 它托着身体,身体又走不开。 */
        record Underfoot() implements Why {}

        /** 走不到够得着、看得见它的地方。 */
        record Unreachable() implements Why {}

        /** 许可或 {@link Effector} 拒绝挖它;{@code reason} 是拒绝方自己的理由。 */
        record Refused(Object reason) implements Why {}
    }

    private static final Why UNDERFOOT = new Why.Underfoot();
    private static final Why UNREACHABLE = new Why.Unreachable();

    /** 撤回的状态。 */
    public enum State {
        RUNNING, DONE
    }

    /** 为什么走这一趟。 */
    private enum Errand {
        /** 从托着自己的垫块上下来。 */
        STEP_OFF,
        /** 走到够得着、看得见某一块的地方。 */
        APPROACH
    }

    private final Rig rig;
    private final RouteSpec spec;
    private final int budget;
    private final Work work;
    /** 还没处理的块:格 → 放下时的样子,按放下的先后。 */
    private final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();
    private final List<BlockPos> taken = new ArrayList<>();
    private final List<Left> left = new ArrayList<>();

    private Driver errand;
    private Errand why;
    /** 走过去要够的那一块;走完还看不见它,它就算够不着。 */
    private BlockPos approached;
    private Edit.Dig digging;
    private State state = State.RUNNING;

    /**
     * @param placed 要撤的块,按放下的先后
     * @param spec   走过去用的规格;必须只走不改
     * @param budget 走过去时每次搜索最多展开几个节点
     */
    public TakeBack(Body body, Effector hands, TerrainPolicy terrain, Threats threats, List<EditLedger.Placed> placed,
                    RouteSpec spec, int budget) {
        if (spec.alter().mayAlter()) {
            throw new IllegalArgumentException("撤回垫块时走过去只走不改,规格却许改地形:" + spec.alter());
        }
        this.rig = new Rig(body, hands, terrain, Materials.NONE, threats);
        this.spec = spec;
        this.budget = budget;
        this.work = new Work(rig, null);
        for (EditLedger.Placed p : placed) {
            pending.put(p.pos(), p.after());
        }
        PathLog.info("{} 撤垫块 {} 块 {} 走过去按 {}", rig.who, pending.size(), pending.keySet().stream().map(PathLog::pos)
                .toList(), PathLog.spec(spec));
    }

    public State state() {
        return state;
    }

    /** 撤掉了的格,按先后。 */
    public List<BlockPos> taken() {
        return Collections.unmodifiableList(taken);
    }

    /** 留在原处的块。 */
    public List<Left> left() {
        return Collections.unmodifiableList(left);
    }

    public EditLedger ledger() {
        return rig.ledger;
    }

    public List<BodyAction> actions() {
        return rig.actions();
    }

    /** 叫停:子导航一起停,松开所有键,手上正在挖的放下。 */
    public void stop() {
        if (errand != null) {
            errand.stop();
            errand = null;
        }
        finish();
    }

    public State tick() {
        if (state != State.RUNNING) {
            return state;
        }
        rig.tally.begin();
        try {
            advance();
        } finally {
            rig.tally.end(rig.who);
        }
        return state;
    }

    /** 推一刻。 */
    private State advance() {
        if (errand != null) {
            runErrand();
            return state;
        }
        forgetChanged();
        if (digging != null) {
            dig();
            return state;
        }
        if (pending.isEmpty()) {
            finish();
            return state;
        }
        ServerPlayer body = rig.entity;
        rig.keys.releaseAll();
        if (!body.onGround()) {
            return state;
        }
        Set<BlockPos> supports = supports();
        if (!supports.isEmpty() && pending.keySet().containsAll(supports)) {
            send(Errand.STEP_OFF, Goals.offBlocks(pending.keySet()));
            return state;
        }
        BlockPos next = next(supports);
        if (next == null) {
            // 剩下的都托着她,而她脚下还有别的方块托着:挖哪一块都得先挪开
            send(Errand.STEP_OFF, Goals.offBlocks(pending.keySet()));
            return state;
        }
        if (Aim.point(body, next) != null) {
            approached = null;
            begin(next);
            dig();
        } else if (next.equals(approached)) {
            leave(next, UNREACHABLE);
        } else {
            approached = next;
            send(Errand.APPROACH, Goals.reach(next, rig.snapshot().stats()));
        }
        return state;
    }

    // ==================== 走一趟 ====================

    private void send(Errand kind, Goal goal) {
        PathLog.debug("{} 撤垫块 走一趟 {} 去 {} {}", rig.who, kind, goal, PathLog.at(rig.entity.position()));
        why = kind;
        errand = new Driver(rig, goal, spec, budget, null);
        runErrand();
    }

    /** 走一趟到了就回去接着挑;走不到:下不来的留下托着她的那几块,够不着的留下要够的那一块。 */
    private void runErrand() {
        Driver.State s = errand.tick();
        if (s == Driver.State.RUNNING) {
            return;
        }
        Halt halt = errand.halt();
        PathLog.debug("{} 撤垫块 走一趟 {} {}{}", rig.who, why, s, halt != null ? " " + halt : "");
        errand = null;
        if (why == Errand.STEP_OFF) {
            // 下来了还踩着的(站定在它与别的方块的交界上)或下不来,托着她的那几块都留下,别的照撤
            for (BlockPos pos : supports()) {
                leave(pos, UNDERFOOT);
            }
        } else if (s != Driver.State.ARRIVED) {
            leave(approached, UNREACHABLE);
        }
    }

    // ==================== 挖 ====================

    /** 下一块撤哪一块:没托着身体的里面,后放的先撤;都托着身体为 null。 */
    private BlockPos next(Set<BlockPos> supports) {
        List<BlockPos> order = new ArrayList<>(pending.keySet());
        Collections.reverse(order);
        for (BlockPos pos : order) {
            if (!supports.contains(pos)) {
                return pos;
            }
        }
        return null;
    }

    private void begin(BlockPos pos) {
        BlockState state = rig.world().getBlockState(pos);
        Permit permit = rig.terrain.judge(TerrainPolicy.Change.DIG, pos, state, rig.world());
        if (permit instanceof Permit.Deny deny) {
            leave(pos, new Why.Refused(deny.reason()));
            return;
        }
        PathLog.debug("{} 撤 {} {} 站在 {}", rig.who, PathLog.pos(pos), PathLog.block(state), PathLog.at(rig.entity.position()));
        digging = new Edit.Dig(pos, state, permit, rig.entity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER),
                rig.entity.onGround());
    }

    private void dig() {
        if (digging == null) {
            return;
        }
        BlockPos pos = digging.pos();
        if (work.done(digging)) {
            pending.remove(pos);
            taken.add(pos);
            digging = null;
            return;
        }
        switch (work.tick(digging)) {
            case Beat.Going going -> {
            }
            case Beat.Denied denied -> {
                rig.hands.release();
                leave(pos, new Why.Refused(denied.reason()));
                digging = null;
            }
            case Beat.Blocked blocked -> {
                rig.hands.release();
                leave(pos, UNREACHABLE);
                digging = null;
            }
        }
    }

    // ==================== 账面 ====================

    /** 已经不是放下时那种方块的格:不是她留下的东西了,不撤也不算留下。 */
    private void forgetChanged() {
        pending.entrySet().removeIf(e -> (digging == null || !digging.pos().equals(e.getKey()))
                && rig.world().getBlockState(e.getKey()).getBlock() != e.getValue().getBlock());
    }

    private void leave(BlockPos pos, Why reason) {
        BlockState placed = pending.remove(pos);
        if (placed != null) {
            PathLog.info("{} 留下垫块 {} {}:{}", rig.who, PathLog.pos(pos), PathLog.block(placed), reason);
            left.add(new Left(pos, placed, reason));
        }
    }

    /** 排障用:还剩哪几块、在不在走一趟。经过见日志({@link PathLog})。 */
    @Override
    public String toString() {
        return "TakeBack[" + state + " pending=" + pending.keySet() + (errand != null ? " " + why + " " + errand : "")
                + "]";
    }

    private void finish() {
        rig.keys.releaseAll();
        rig.hands.release();
        if (state == State.RUNNING) {
            PathLog.info("{} 撤垫块完 撤掉 {} 块 留下 {} 块{}", rig.who, taken.size(), left.size(),
                    pending.isEmpty() ? "" : ",叫停时还有 " + pending.size() + " 块没撤");
        }
        state = State.DONE;
    }

    /** 此刻托着身体的方块。 */
    private Set<BlockPos> supports() {
        return Footing.supports(rig.world(), rig.snapshot().stats(), rig.entity.getBoundingBox());
    }
}
