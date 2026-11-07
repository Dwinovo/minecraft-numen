package com.dwinovo.numen.api.entity;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 一具服务端假玩家的键盘,一具身体一副(同伴的是 {@link NumenPlayer#controls})。键按下就一直按着,直到松开;每刻由 {@link Physics#step}
 * 在身体的物理步进之前落一次,照原版客户端把按键变成身体输入的那一套({@code KeyboardInput.tick} 与
 * {@code LocalPlayer.aiStep})落到身体上——假玩家没有客户端,服务端缺的就是这一段:
 * <ul>
 *   <li>前后左右是数字键:{@code zza}、{@code xxa} 只取 -1、0、1,相对身体此刻的朝向;蹲着或爬着乘潜行速度属性,
 *       用着物品乘 0.2;</li>
 *   <li>跳是按住:原版服务端的 {@code aiStep} 按住跳时自己分地面起跳、水里上浮、攀爬上爬;</li>
 *   <li>潜行交给服务端的姿势与"不走出边沿";在水里按着潜行往下沉(原版客户端的 {@code goDownInWater});</li>
 *   <li>疾跑照原版客户端的条件开始与中止:前冲够大、吃得够饱(或能飞)、没用物品、没失明;撞墙、松开前进、饿了、在水面
 *       上就停。唯一的不同是松开疾跑键就停跑——键盘上要停跑得松一下前进键,这里把两件事合成一个键;</li>
 *   <li>身体卡在方块里时,照原版客户端每刻往最近的空处推一下({@code LocalPlayer.moveTowardsClosestSpace});</li>
 *   <li>创造飞行:许飞的身体(能力 {@code mayfly})在 {@value #DOUBLE_TAP} 刻之内连按两下跳(松开再按)切换飞行,切的方式是客户端发的那个能力包,
 *       许不许由原版服务端照 {@code mayfly} 判;从地面切上去时照原版起跳一次。飞着按跳往上、按潜行往下(每刻 ±3 × 飞行速度),
 *       飞着落了地就停飞({@code LocalPlayer.aiStep} 的末尾);</li>
 *   <li>鞘翅:没在飞行、没骑乘、没攀着、胸甲位是没坏的鞘翅时,按下跳的那一刻发展开鞘翅的玩家指令,在不在空中、水里、有没有漂浮效果由原版服务端判;
 *       {@link #unfurl} 是同一件事,不等按键。</li>
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
    /** 双击跳的窗口(原版 {@code LocalPlayer.aiStep} 的 {@code jumpTriggerTime = 7},{@code Player.aiStep} 每刻减一)。 */
    public static final int DOUBLE_TAP = 7;
    /** 飞着按跳、按潜行每刻加的竖直速度是飞行速度的几倍(原版 {@code LocalPlayer.aiStep} 的 {@code * 3.0F})。 */
    public static final float FLY_LIFT = 3.0F;

    private final EnumSet<Key> held = EnumSet.noneOf(Key.class);
    /** 上一刻跳键按没按着:按下沿(这一刻按着、上一刻没按)才算"按了一下"。 */
    private boolean jumpedBefore;
    /** 第一下跳之后还剩几刻能接第二下;0 是没有在等第二下。 */
    private int doubleTap;

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

    /**
     * 此刻按下跳,会不会被当成双击的第二下而切换飞行:第一下之后的窗口里、跳键松着时是。飞着靠跳升高的控制器按之前问它——
     * 松开又按、隔得不到 {@value #DOUBLE_TAP} 刻,就把飞行关了。
     */
    public boolean jumpWouldToggleFlight() {
        return doubleTap > 0 && !held(Key.JUMP);
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
        boolean pressed = held(Key.JUMP) && !jumpedBefore;
        jumpedBefore = held(Key.JUMP);
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
        boolean toggled = toggleFlight(body, pressed);
        if (pressed && !toggled) {
            unfurl(body);
        }
        if (body.isInWater() && held(Key.SNEAK) && body.isAffectedByFluids()) {
            body.setDeltaMovement(body.getDeltaMovement().add(0, SINK, 0));
        }
        if (body.getAbilities().flying) {
            lift(body);
        }
        if (doubleTap > 0) {
            doubleTap--;
        }
    }

    /**
     * 身体这一刻的物理步进做完之后:飞着落了地就停飞。原版客户端在 {@code aiStep} 末尾做,发的同样是能力包。只由 {@link Physics#step} 调。
     */
    void afterTick(ServerPlayer body) {
        if (body.getAbilities().flying && body.onGround()) {
            new ClientPackets(body).flying(false);
        }
    }

    /**
     * 许飞的身体按下了一下跳:{@value #DOUBLE_TAP} 刻之内的第二下切换飞行(游着时不切),从地面切上去时起跳一次。
     * 能力包发出去,服务端照 {@code mayfly} 判,切没切成读回来的能力就是。
     *
     * @return 这一刻切换了飞行(同一下跳不再去展开鞘翅)
     */
    private boolean toggleFlight(ServerPlayer body, boolean pressed) {
        Abilities abilities = body.getAbilities();
        if (!abilities.mayfly || !pressed) {
            return false;
        }
        if (doubleTap == 0) {
            doubleTap = DOUBLE_TAP;
            return false;
        }
        if (body.isSwimming()) {
            return false;
        }
        new ClientPackets(body).flying(!abilities.flying);
        if (abilities.flying && body.onGround()) {
            body.jumpFromGround();
        }
        doubleTap = 0;
        return true;
    }

    /** 飞着按跳往上、按潜行往下:这一刻往竖直速度里加 ±3 × 飞行速度。 */
    private void lift(ServerPlayer body) {
        int direction = (held(Key.JUMP) ? 1 : 0) - (held(Key.SNEAK) ? 1 : 0);
        if (direction != 0) {
            body.setDeltaMovement(body.getDeltaMovement().add(0.0,
                    (double) ((float) direction * body.getAbilities().getFlyingSpeed() * FLY_LIFT), 0.0));
        }
    }

    /**
     * 展开鞘翅:发展开的玩家指令(原版客户端在空中按下跳时发的那个),在不在空中、水里、有没有漂浮效果由原版服务端判。
     * 飞着(创造飞行)、骑乘、攀着、没穿没坏的鞘翅时客户端不发(1.21.2 起"没坏"是 {@code nextDamageWillBreak} 为假)。展开的流程只此一处:按键走这里,{@code numen.use.glide} 也走这里。
     *
     * @return 身体此刻在滑翔(展开成了,或本来就在滑翔)
     */
    public boolean unfurl(ServerPlayer body) {
        if (body.isFallFlying()) {
            return true;
        }
        var chest = body.getItemBySlot(EquipmentSlot.CHEST);
        if (body.getAbilities().flying || body.isPassenger() || body.onClimbable() || !chest.is(Items.ELYTRA)
                || chest.nextDamageWillBreak()) {
            return false;
        }
        new ClientPackets(body).command(ServerboundPlayerCommandPacket.Action.START_FALL_FLYING);
        return body.isFallFlying();
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
