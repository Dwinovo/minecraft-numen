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
    private static final int GAP_MIN_TICKS = 60, GAP_MAX_TICKS = 200;    // 动作间歇 3~10s(原版宠物档)
    private static final int GLANCE_MIN_TICKS = 30, GLANCE_MAX_TICKS = 70;
    private static final int WANDER_TIMEOUT_TICKS = 120;
    private static final int PICK_ATTEMPTS = 8;
    private static final double ARRIVE_DIST_SQR = 1.2 * 1.2;

    private enum Episode { NONE, GLANCE, WANDER }

    private Episode episode = Episode.NONE;
    private long nextEpisodeAt;
    private int episodeTicksLeft;
    private BlockPos strollTarget;
    private net.minecraft.world.phys.Vec3 lastPos = net.minecraft.world.phys.Vec3.ZERO;
    private int stuckTicks;
    private final Random rng = new Random();

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!ReflexRegistry.enabled(id())) return Float.NEGATIVE_INFINITY;
        // 说话姿态:大脑在输出且主人在近旁——持续注视,无视间隔。任务在跑时
        // LLM 链出价更高,自然让位(挖着矿说话不回头,合理)。
        if (com.dwinovo.numen.entity.CompanionSpeech.isSpeaking(companion.getUUID())
                && ownerNearby(companion, GLANCE_RANGE + 4)) {
            return IDLE_PRIORITY;
        }
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
        // 说话姿态优先:停下小动作,面向主人说话。结束后小动作从中断处继续。
        if (com.dwinovo.numen.entity.CompanionSpeech.isSpeaking(companion.getUUID())) {
            InputDriver.halt(companion);
            InputDriver.lookAt(companion, owner.getEyePosition());
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
                // 原版味道的三条走路守卫:临崖收脚、见水止步、卡墙放弃。
                // 走不到就算了——这是闲逛,不是任务。
                if (companion.isInWater() || cliffAhead(companion)) {
                    end(companion);
                    return;
                }
                InputDriver.stepToward(companion, aim, false);
                if (companion.horizontalCollision) {
                    InputDriver.jump(companion);                 // 台阶自动跳,原版自动跳同款手感
                }
                if (companion.position().distanceToSqr(lastPos) < 0.0004) {   // ~0.02 格/tick 都不到
                    if (++stuckTicks >= 20) {
                        end(companion);
                        return;
                    }
                } else {
                    stuckTicks = 0;
                    lastPos = companion.position();
                }
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
        // 四成溜达六成看人;看不着人(距离)就只考虑溜达(方向朝主人偏)。
        if (nearOwner && rng.nextInt(100) < 60) {
            episode = Episode.GLANCE;
            episodeTicksLeft = GLANCE_MIN_TICKS + rng.nextInt(GLANCE_MAX_TICKS - GLANCE_MIN_TICKS);
            com.dwinovo.numen.Constants.LOG.debug("[numen-idle#{}] glance at owner ({} ticks)",
                    companion.getUUID(), episodeTicksLeft);
            return;
        }
        BlockPos target = pickStroll(companion, owner);
        if (target == null) {
            com.dwinovo.numen.Constants.LOG.debug("[numen-idle#{}] no clean stroll line, skipping",
                    companion.getUUID());
            end(companion);   // 附近没有一条能走的缓坡线,这次不散了
            return;
        }
        strollTarget = target;
        episode = Episode.WANDER;
        episodeTicksLeft = WANDER_TIMEOUT_TICKS;
        lastPos = companion.position();
        stuckTicks = 0;
        com.dwinovo.numen.Constants.LOG.debug("[numen-idle#{}] stroll to {}", companion.getUUID(), target);
    }

    /**
     * 挑散步点,原版 RandomStroll 的味道:随机方向随机步幅,落脚点能站人、
     * 不出主人的拴绳圈,就出发——不做线路预检,走的时候三条守卫兜底,
     * 走不到就算了。主人在瞟视距离外时方向朝主人偏,闲逛顺便凑近。
     */
    private BlockPos pickStroll(NumenPlayer companion, ServerPlayer owner) {
        Level level = companion.level();
        BlockPos feet = companion.blockPosition();
        boolean towardOwner = !companion.blockPosition().closerThan(owner.blockPosition(), GLANCE_RANGE);
        for (int i = 0; i < PICK_ATTEMPTS; i++) {
            int sx = towardOwner ? Integer.signum(owner.blockPosition().getX() - feet.getX())
                    : (rng.nextBoolean() ? 1 : -1);
            int sz = towardOwner ? Integer.signum(owner.blockPosition().getZ() - feet.getZ())
                    : (rng.nextBoolean() ? 1 : -1);
            if (sx == 0) sx = rng.nextBoolean() ? 1 : -1;
            if (sz == 0) sz = rng.nextBoolean() ? 1 : -1;
            int dx = sx * (STROLL_MIN + rng.nextInt(STROLL_MAX - STROLL_MIN + 1));
            int dz = sz * (STROLL_MIN + rng.nextInt(STROLL_MAX - STROLL_MIN + 1));
            BlockPos candidate = adjustToStandable(level, feet.offset(dx, 0, dz));
            if (candidate == null) continue;
            if (!candidate.closerThan(owner.blockPosition(), LEASH + (towardOwner ? OWNER_RANGE : 0))) continue;
            return candidate;
        }
        return null;
    }

    private static boolean ownerNearby(NumenPlayer companion, int range) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        return owner != null && owner.level() == companion.level()
                && companion.blockPosition().closerThan(owner.blockPosition(), range);
    }

    /** 候选格上下各 1 格内找可站立的落脚点(脚/头无碰撞、脚下实心)。 */
    private static BlockPos adjustToStandable(Level level, BlockPos pos) {
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos p = pos.above(dy);
            if (standable(level, p)) return p;
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

    /** 临崖守卫:面前一格落差超过 2 就收脚——原版宠物靠寻路避崖,闲逛靠这条。 */
    private static boolean cliffAhead(NumenPlayer companion) {
        net.minecraft.world.phys.Vec3 dir = companion.getLookAngle();
        BlockPos ahead = companion.blockPosition().offset(
                (int) Math.signum(dir.x), 0, (int) Math.signum(dir.z));
        Level level = companion.level();
        if (!passable(level, ahead)) return false;   // 前面是墙/台阶,不是崖
        for (int dy = 1; dy <= 3; dy++) {
            if (!passable(level, ahead.below(dy))) return false;   // 3 格内有地,安全
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
