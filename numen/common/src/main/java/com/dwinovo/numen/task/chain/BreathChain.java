package com.dwinovo.numen.task.chain;

import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskRecord;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.api.task.reflex.Reflex;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.nav.Trip;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.task.reflex.NumenReflexes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 换气本能:只管真缺氧。头在水里,游到最近能呼吸的地方要多久({@link Opening#ticks})算上憋气的余量({@link Breath#RESERVE})
 * 肺里的氧气撑不住了,才接管身体往上游;氧气和余量的规则全在寻路的 {@link Breath}(水下呼吸效果、附魔、海龟壳、创造模式不扣氧都
 * 在里头),这里不另起一套。浮在水面上、潜在水里闲着都不归它管:闲着的漂浮是身体的空闲姿态
 * ({@link com.dwinovo.numen.api.task.chain.FloatChain}),整个人在水下时不往上拽。
 *
 * <p>路线里计划好的一段水下({@link Trip#plannedDive})不插手:憋多久寻路规划时已按她的氧气算过。
 *
 * <p>接管后往上游。头顶是开阔的水就一路往上;头顶被冰面、岩层封住,就在连通的水里 BFS 找最近的、上面有空气的那一格,
 * 朝它游、同时按住跳。附近找不到开口,作为急件告诉她(她得当场决定挖气孔还是想别的办法)。
 *
 * <p>什么时候松手:头出了水,而且(已经离开水,或氧气回满)。头一露出水面就松手的话,被它顶掉的任务会接着下潜,氧气才回了几点又触发,
 * 一沉一浮;等回满,下一次触发离得最远。出水后闲着的话,漂浮姿态接着把她浮在水面上。被它顶掉的任务只是冻结,松手后接着做。
 *
 * <p>每次接管只告诉她一条:顶掉了什么(闲着就说闲着)、为什么。找不到开口的那条是急件,其余随下一次调模型捎带。
 */
public final class BreathChain implements Task, Reflex {

    /** 头顶往上探多高,探完还是水就当开阔水域:一路往上游就对了。 */
    private static final int CEILING_PROBE = 16;
    /** 找透气口时在连通的水里最多读多少格。 */
    private static final int AIR_SEARCH_BUDGET = 400;
    /** 找透气口的水平范围(每个轴,距起点那一列多少格)。 */
    private static final int AIR_SEARCH_RADIUS = 16;
    /** 多久重新找一次透气口(刻):算出来的游程和朝向的那一格都按这个节奏更新。 */
    private static final int RETARGET_TICKS = 20;

    /**
     * 最近能呼吸的地方。
     *
     * @param column 头顶封住时朝它游的那一格(它是水、上面有空气);头顶是开阔的水时为 null,一路往上游
     * @param steps  游到那里的步数(水里每步一格,和规划同口径);没找到时是找过的范围,真正的开口只会更远
     * @param found  找没找到;封在下面、范围内没有开口是 false
     * @param ticks  游那么多步要几刻({@link Snapshots#swimTicksPerBlock})
     */
    private record Opening(BlockPos column, int steps, boolean found, double ticks) {}

    /** 正在接管。 */
    private boolean active;
    private Opening opening;
    private long locatedAt;

    public BreathChain() {
    }

    /**
     * 此刻憋不憋得住:游到最近能呼吸的地方({@code swimTicks} 刻,全程眼睛在水里),到那里肺里还剩不剩 {@link Breath#RESERVE}。
     * 撑不住就该上去了。
     */
    static boolean mustSurface(Breath breath, double swimTicks) {
        return !breath.lasts(breath.after(breath.now(), true, swimTicks));
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (active) {
            if (stillSurfacing(companion)) {
                return true;
            }
            active = false;
            opening = null;
            return false;
        }
        if (!companion.isEyeInFluid(FluidTags.WATER)) {
            opening = null;
            return false;
        }
        Trip trip = Trip.current(companion);
        if (trip != null && trip.plannedDive()) {
            // 在走的路线里计划好的一段水下:憋多久寻路规划时已按她的氧气算过,每一刻再按真实氧气判剩下的这一段撑不撑得到,
            // 撑不到的那一刻起就不再是计划内的(寻路停下那一步)。这条本能只接管计划外的,不在计划内的水下半路把她拽上去。
            return false;
        }
        return mustSurface(Snapshots.breath(companion), locate(companion).ticks());
    }

    /**
     * 还没换够气:头还在水里,或者头出了水、氧气没回满而且人还没站上干地(浮在水面上,或在水上方的气室里漂着——脚不泡水也算,
     * 不能看 {@code isInWater})。上了岸站稳就不必再按着她等气回满,去哪儿由她手上的活定。
     */
    private static boolean stillSurfacing(NumenPlayer companion) {
        if (companion.isEyeInFluid(FluidTags.WATER)) {
            return true;
        }
        boolean onDryGround = companion.onGround() && !companion.isInWater();
        return !onDryGround && companion.getAirSupply() < companion.getMaxAirSupply();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (!active) {
            active = true;
            tell(companion, locate(companion));
        }
        companion.controls().releaseAll();
        if (companion.isEyeInFluid(FluidTags.WATER)) {
            // 开阔的水一路往上就够;封住的头顶才朝透气口游,同时按住跳
            Opening toward = locate(companion);
            if (toward.column() != null) {
                companion.look().faceToward(toward.column().getX() + 0.5, toward.column().getZ() + 0.5);
                companion.controls().press(Controls.Key.FORWARD);
            }
        }
        companion.controls().press(Controls.Key.JUMP);   // 在水里按住跳就是往上游
        return TaskState.RUNNING;
    }

    /** 最近的透气口:隔 {@link #RETARGET_TICKS} 刻重找一次,朝着的那一格没了立刻重找。 */
    private Opening locate(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        if (opening == null || now - locatedAt >= RETARGET_TICKS
                || opening.column() != null && !breathableAbove(companion.level(), opening.column())) {
            opening = search(companion);
            locatedAt = now;
        }
        return opening;
    }

    private static Opening search(NumenPlayer companion) {
        double perBlock = Snapshots.swimTicksPerBlock(companion);
        Level level = companion.level();
        BlockPos eye = BlockPos.containing(companion.getEyePosition());
        // 头顶先笔直往上探:够到空气就是开阔水;探满了还是水,往上游就对了(游程只算到探到的高度);被封住才找透气口
        BlockPos p = eye;
        for (int i = 1; i <= CEILING_PROBE; i++) {
            p = p.above();
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            if (breathable(level, p, s)) {
                return new Opening(null, i, true, i * perBlock);
            }
            return seek(level, companion, perBlock);
        }
        return new Opening(null, CEILING_PROBE, true, CEILING_PROBE * perBlock);
    }

    /** 在连通的水里 BFS 找最近的、上面有空气的那一格;按游的步数算最近,不是直线上的假象。 */
    private static Opening seek(Level level, NumenPlayer companion, double perBlock) {
        BlockPos start = BlockPos.containing(companion.getEyePosition());
        if (!level.getFluidState(start).is(FluidTags.WATER)) {
            start = companion.blockPosition();
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<Long, Integer> steps = new HashMap<>();
        queue.add(start);
        steps.put(start.asLong(), 0);
        int budget = AIR_SEARCH_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            int here = steps.get(cell.asLong());
            if (breathableAbove(level, cell)) {
                // 游到那一格再往上一步才露头
                return new Opening(cell, here + 1, true, (here + 1) * perBlock);
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (Math.abs(n.getX() - start.getX()) > AIR_SEARCH_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > AIR_SEARCH_RADIUS) continue;
                if (!level.getFluidState(n).is(FluidTags.WATER)) continue;
                if (steps.putIfAbsent(n.asLong(), here + 1) == null) {
                    queue.add(n);
                }
            }
        }
        // 范围内没有开口:真正的开口只会比找过的范围更远
        return new Opening(null, AIR_SEARCH_RADIUS, false, AIR_SEARCH_RADIUS * perBlock);
    }

    /** 头可以呼吸的一格:没有流体,也没有东西挡着。 */
    private static boolean breathable(Level level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** {@code waterCell} 还是个透气口:是水,上面有空气。 */
    private static boolean breathableAbove(Level level, BlockPos waterCell) {
        if (!level.getFluidState(waterCell).is(FluidTags.WATER)) return false;
        BlockPos above = waterCell.above();
        return breathable(level, above, level.getBlockState(above));
    }

    /** 这一次接管只告诉她一条:顶掉了什么、为什么;找不到开口是急件。 */
    private void tell(NumenPlayer companion, Opening toward) {
        TaskRecord<?> paused = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        String air = Math.max(0, companion.getAirSupply() / 20) + "s of air";
        String interrupted = paused == null ? "I was idle under water" : "I paused \"" + paused.describe() + "\"";
        if (!toward.found()) {
            NumenReflexes.alert(companion, this, interrupted + ": drowning under a sealed ceiling with " + air
                    + " left and no opening within " + AIR_SEARCH_RADIUS
                    + " blocks of connected water; I need an air hole dug or a way out");
            return;
        }
        NumenReflexes.report(companion, this, interrupted + " to swim up for a breath: " + air + " left, about "
                + Math.round(toward.ticks() / 20) + "s from air" + (paused == null ? "" : "; it carries on once my air is back"));
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // 没有跨刻的身体状态要收;接管到换够气才算完,被更先的反射顶掉也只是暂停
    }

    @Override
    public String name() {
        return "breath";
    }

    // ---- 本能名册文书(宪法 §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "在水里游到最近能呼吸处的工夫快超过肺里的氧气时会自己浮上来换气,换满再放手让你的活接着做;头顶被冰面/岩层封住时会游向最近的透气口";
    }
}
