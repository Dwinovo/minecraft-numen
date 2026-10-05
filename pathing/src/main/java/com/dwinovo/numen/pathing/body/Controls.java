package com.dwinovo.numen.pathing.body;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的键盘,一具身体一副({@link Body#controls})。键按下就一直按着,直到松开;每刻由 {@link Physics#step}
 * 在身体的物理步进之前落一次,照原版客户端把按键变成身体输入的那一套({@code KeyboardInput.tick} 与
 * {@code LocalPlayer.aiStep})落到身体上——假玩家没有客户端,服务端缺的就是这一段:
 * <ul>
 *   <li>前后左右是数字键:{@code zza}、{@code xxa} 只取 -1、0、1,相对身体此刻的朝向;蹲着或爬着乘潜行速度属性,
 *       用着物品乘 0.2;</li>
 *   <li>跳是按住:原版服务端的 {@code aiStep} 按住跳时自己分地面起跳、水里上浮、攀爬上爬;</li>
 *   <li>潜行交给服务端的姿势与"不走出边沿";在水里按着潜行往下沉(原版客户端的 {@code goDownInWater});</li>
 *   <li>疾跑照原版客户端的条件开始与中止:前冲够大、吃得够饱(或能飞)、没用物品、没失明;撞墙、松开前进、饿了、在水面
 *       上就停。唯一的不同是松开疾跑键就停跑——键盘上要停跑得松一下前进键,这里把两件事合成一个键;</li>
 *   <li>身体卡在方块里时,照原版客户端每刻往最近的空处推一下({@code LocalPlayer.moveTowardsClosestSpace})。</li>
 * </ul>
 * 视角不在这里:朝向由执行层的瞄准直接转。
 */
public final class Controls {

    /** 键。 */
    public enum Key {
        FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT
    }

    /** 原版客户端判"前冲够大才能开跑"的门槛。 */
    private static final double SPRINT_IMPULSE = 0.8;
    /** 用着物品时的走速({@code LocalPlayer.aiStep})。 */
    private static final float USING_ITEM_SLOWDOWN = 0.2F;
    /** 按着潜行时每刻往下沉多少(原版 {@code LivingEntity.goDownInWater})。 */
    private static final double SINK = -0.04;
    /** 卡在方块里时每刻往外推的速度(原版 {@code LocalPlayer.moveTowardsClosestSpace})。 */
    private static final double PUSH_OUT = 0.1;

    private final EnumSet<Key> held = EnumSet.noneOf(Key.class);

    public void press(Key key) {
        held.add(key);
    }

    public void release(Key key) {
        held.remove(key);
    }

    public void set(Key key, boolean down) {
        if (down) {
            held.add(key);
        } else {
            held.remove(key);
        }
    }

    public boolean held(Key key) {
        return held.contains(key);
    }

    /** 松开所有键。 */
    public void releaseAll() {
        held.clear();
    }

    /** 停下脚步:松开前后左右、跳与疾跑,潜行照旧按着(蹲在边沿上干活时停下不站起来)。 */
    public void stop() {
        held.removeAll(MOVING);
    }

    private static final EnumSet<Key> MOVING = EnumSet.of(Key.FORWARD, Key.BACK, Key.LEFT, Key.RIGHT, Key.JUMP,
            Key.SPRINT);

    /** 把按着的键落到身体上。只由 {@link Physics#step} 调,每刻一次。 */
    void apply(ServerPlayer body) {
        pushOutOfBlocks(body);
        float forward = impulse(Key.FORWARD, Key.BACK);
        float left = impulse(Key.LEFT, Key.RIGHT);
        if (body.isCrouching() || body.isVisuallyCrawling()) {
            float slow = (float) body.getAttributeValue(Attributes.SNEAKING_SPEED);
            forward *= slow;
            left *= slow;
        }
        if (body.isUsingItem() && !body.isPassenger()) {
            forward *= USING_ITEM_SLOWDOWN;
            left *= USING_ITEM_SLOWDOWN;
        }
        body.zza = forward;
        body.xxa = left;
        body.setJumping(held(Key.JUMP));
        body.setShiftKeyDown(held(Key.SNEAK));
        sprint(body, forward);
        if (body.isInWater() && held(Key.SNEAK) && body.isAffectedByFluids()) {
            body.setDeltaMovement(body.getDeltaMovement().add(0, SINK, 0));
        }
    }

    private float impulse(Key positive, Key negative) {
        boolean p = held(positive);
        boolean n = held(negative);
        return p == n ? 0 : p ? 1 : -1;
    }

    /** 原版客户端的开跑与停跑。 */
    private void sprint(ServerPlayer body, float forward) {
        boolean forwardImpulse = forward > 1.0E-5F;
        boolean fed = body.getFoodData().getFoodLevel() > 6 || body.getAbilities().mayfly;
        if (!body.isSprinting()) {
            boolean impulse = body.isUnderWater() ? forwardImpulse : forward >= SPRINT_IMPULSE;
            boolean surface = body.isInWater() && !body.isUnderWater();
            if (held(Key.SPRINT) && impulse && fed && !body.isUsingItem() && !body.hasEffect(MobEffects.BLINDNESS)
                    && !body.isPassenger() && !body.isFallFlying() && !surface) {
                body.setSprinting(true);
            }
            return;
        }
        boolean spent = !forwardImpulse || !fed;
        boolean stop = body.isSwimming()
                ? !body.onGround() && !held(Key.SNEAK) && spent || !body.isInWater()
                : spent || body.horizontalCollision && !body.minorHorizontalCollision
                        || body.isInWater() && !body.isUnderWater();
        if (stop || !held(Key.SPRINT)) {
            body.setSprinting(false);
        }
    }

    /** 原版客户端:身体脚底的四个角各看一次,陷在会窒息的方块里就朝最近的空处推。 */
    private static void pushOutOfBlocks(ServerPlayer body) {
        if (body.noPhysics) {
            return;
        }
        double reach = body.getBbWidth() * 0.35;
        pushOut(body, body.getX() - reach, body.getZ() + reach);
        pushOut(body, body.getX() - reach, body.getZ() - reach);
        pushOut(body, body.getX() + reach, body.getZ() - reach);
        pushOut(body, body.getX() + reach, body.getZ() + reach);
    }

    private static final Direction[] SIDES = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

    private static void pushOut(ServerPlayer body, double x, double z) {
        BlockPos cell = BlockPos.containing(x, body.getY(), z);
        if (!suffocatesAt(body, cell)) {
            return;
        }
        double inX = x - cell.getX();
        double inZ = z - cell.getZ();
        Direction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction side : SIDES) {
            double along = side.getAxis().choose(inX, 0.0, inZ);
            double distance = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - along : along;
            if (distance < bestDistance && !suffocatesAt(body, cell.relative(side))) {
                bestDistance = distance;
                best = side;
            }
        }
        if (best != null) {
            Vec3 motion = body.getDeltaMovement();
            body.setDeltaMovement(best.getAxis() == Direction.Axis.X
                    ? new Vec3(PUSH_OUT * best.getStepX(), motion.y, motion.z)
                    : new Vec3(motion.x, motion.y, PUSH_OUT * best.getStepZ()));
        }
    }

    private static boolean suffocatesAt(ServerPlayer body, BlockPos cell) {
        AABB box = body.getBoundingBox();
        AABB column = new AABB(cell.getX(), box.minY, cell.getZ(), cell.getX() + 1.0, box.maxY, cell.getZ() + 1.0)
                .deflate(1.0E-7);
        return body.level().collidesWithSuffocatingBlock(body, column);
    }
}
