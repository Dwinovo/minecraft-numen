package com.dwinovo.numen.task.chain;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.TaskChain;
import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.task.reflex.ReflexRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Random;

/**
 * 闲时生命体征——出价最低的竞价者(LLM 基准价再减一):只有当任务、聊天、
 * 一切本能都休眠时才中标,让同伴不再是一尊雕像。两个小动作:
 *
 * <ul>
 *   <li><b>看主人</b>:主人在近旁时,偶尔把头转过去注视两三秒;</li>
 *   <li><b>溜达</b>:在主人身边几格内散两步,拴着绳(不越出 {@link #LEASH})。</li>
 * </ul>
 *
 * <p>三条纪律:<b>episodic</b>——两次小动作之间彻底休眠(隔 8~25 秒),不霸占
 * 竞价也不常驻盯人;<b>不进 BodyLog</b>——氛围不是叙事,"我看了看主人"这种
 * 日记只会把收件箱变成垃圾场;<b>绝不改地形</b>——溜达不用寻路,选点时要求
 * 整条直线同一水平面且逐格纯可走(脚/头无碰撞、脚下有支撑),挑不出干净的
 * 平路就不散步,之后由 {@link InputDriver} 直线走过去,物理上没有挖掘入口。
 */
public final class IdleChain implements TaskChain, Reflex {

    /** LLM 基准价再低一档:任何任务、任何本能都能立刻抢走身体。 */
    private static final float IDLE_PRIORITY = TaskChain.LLM_BASE_PRIORITY - 1.0f;

    private static final int OWNER_RANGE = 24;       // 主人不在近旁就不演——没有观众
    private static final int GLANCE_RANGE = 12;      // 看主人的距离上限
    private static final int LEASH = 8;              // 溜达不离主人这么多格
    private static final int STROLL_MIN = 2, STROLL_MAX = 5;   // 单次散步的步幅
    private static final int GAP_MIN_TICKS = 160, GAP_MAX_TICKS = 500;   // 动作间歇 8~25s
    private static final int GLANCE_MIN_TICKS = 30, GLANCE_MAX_TICKS = 70;
    private static final int WANDER_TIMEOUT_TICKS = 120;
    private static final int PICK_ATTEMPTS = 8;
    private static final double ARRIVE_DIST_SQR = 1.2 * 1.2;

    private enum Episode { NONE, GLANCE, WANDER }

    private Episode episode = Episode.NONE;
    private long nextEpisodeAt;
    private int episodeTicksLeft;
    private BlockPos strollTarget;
    private final Random rng = new Random();

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!ReflexRegistry.enabled(id())) return Float.NEGATIVE_INFINITY;
        if (episode != Episode.NONE) return IDLE_PRIORITY;   // 把这一幕演完(除非被抢占)
        long now = companion.level().getGameTime();
        if (now < nextEpisodeAt) return Float.NEGATIVE_INFINITY;
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null || owner.level() != companion.level()
                || !companion.blockPosition().closerThan(owner.blockPosition(), OWNER_RANGE)) {
            nextEpisodeAt = now + GAP_MIN_TICKS;   // 没观众,过会儿再看看
            return Float.NEGATIVE_INFINITY;
        }
        return IDLE_PRIORITY;
    }

    @Override
    public void tick(NumenPlayer companion) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            end(companion);
            return;
        }
        if (episode == Episode.NONE) {
            begin(companion, owner);
            if (episode == Episode.NONE) return;   // 没挑出合适的动作,这次作罢
        }
        switch (episode) {
            case GLANCE -> {
                InputDriver.lookAt(companion, owner.getEyePosition());
                if (--episodeTicksLeft <= 0
                        || !companion.blockPosition().closerThan(owner.blockPosition(), GLANCE_RANGE + 4)) {
                    end(companion);
                }
            }
            case WANDER -> {
                if (strollTarget == null) {
                    end(companion);
                    return;
                }
                net.minecraft.world.phys.Vec3 aim =
                        net.minecraft.world.phys.Vec3.atBottomCenterOf(strollTarget);
                if (companion.position().distanceToSqr(aim.x, companion.getY(), aim.z) <= ARRIVE_DIST_SQR
                        || --episodeTicksLeft <= 0) {
                    end(companion);
                    return;
                }
                InputDriver.stepToward(companion, aim, false);   // 选点已保证纯平直线,直走即可
            }
            case NONE -> { }
        }
    }

    @Override
    public void onInterrupt(NumenPlayer companion) {
        end(companion);
    }

    @Override
    public String name() {
        return "idle_life";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "闲下来会看看主人,在主人身边散散步";
    }

    // ---- episodes ----

    private void begin(NumenPlayer companion, ServerPlayer owner) {
        boolean nearOwner = companion.blockPosition().closerThan(owner.blockPosition(), GLANCE_RANGE);
        // 四成溜达六成看人;看不着人(距离)就只考虑溜达。
        if (nearOwner && rng.nextInt(100) < 60) {
            episode = Episode.GLANCE;
            episodeTicksLeft = GLANCE_MIN_TICKS + rng.nextInt(GLANCE_MAX_TICKS - GLANCE_MIN_TICKS);
            return;
        }
        BlockPos target = pickStroll(companion, owner);
        if (target == null) {
            end(companion);   // 附近没有一条干净的平路,这次不散了
            return;
        }
        strollTarget = target;
        episode = Episode.WANDER;
        episodeTicksLeft = WANDER_TIMEOUT_TICKS;
    }

    /**
     * 挑一个"干净"的散步点:随机步幅、拴在主人 {@link #LEASH} 格内、与当前
     * 位置<b>同一水平面</b>,且直线上每一格都纯可走(脚/头无碰撞、脚下实心)。
     * 全平地是硬要求——直走不会跳跃也不会跌落;挑不出来返回 null,宁可不散步。
     */
    private BlockPos pickStroll(NumenPlayer companion, ServerPlayer owner) {
        Level level = companion.level();
        BlockPos feet = companion.blockPosition();
        for (int i = 0; i < PICK_ATTEMPTS; i++) {
            int dx = (rng.nextBoolean() ? 1 : -1) * (STROLL_MIN + rng.nextInt(STROLL_MAX - STROLL_MIN + 1));
            int dz = (rng.nextBoolean() ? 1 : -1) * (STROLL_MIN + rng.nextInt(STROLL_MAX - STROLL_MIN + 1));
            BlockPos candidate = feet.offset(dx, 0, dz);
            if (!standable(level, candidate)) continue;
            if (!candidate.closerThan(owner.blockPosition(), LEASH)) continue;
            if (!lineWalkable(level, feet, candidate)) continue;
            return candidate;
        }
        return null;
    }

    private static boolean standable(Level level, BlockPos feet) {
        return passable(level, feet) && passable(level, feet.above())
                && !passable(level, feet.below());
    }

    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 直线可走性:同一水平面逐格采样,每格都得能站。 */
    private static boolean lineWalkable(Level level, BlockPos from, BlockPos to) {
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        for (int s = 1; s <= steps; s++) {
            int x = from.getX() + Math.round((to.getX() - from.getX()) * (float) s / steps);
            int z = from.getZ() + Math.round((to.getZ() - from.getZ()) * (float) s / steps);
            if (!standable(level, new BlockPos(x, from.getY(), z))) return false;
        }
        return true;
    }

    private void end(NumenPlayer companion) {
        InputDriver.halt(companion);
        strollTarget = null;
        episode = Episode.NONE;
        nextEpisodeAt = companion.level().getGameTime()
                + GAP_MIN_TICKS + rng.nextInt(GAP_MAX_TICKS - GAP_MIN_TICKS);
    }
}
