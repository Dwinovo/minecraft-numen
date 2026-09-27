package com.dwinovo.numen.pathing.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.body.PlayerHands;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 看着身体的手挖:每一格从哪一刻开挖(左键按下去、原版开始累加进度)、哪一刻碎、开挖时手上是什么、眼睛在不在水里、脚着不着地、
 * 原版这一下每刻的进度是多少,以及这一格一共开挖了几次(中途换靶、被当成换了工具,都会重开)。包在身体原版的两只手外面,
 * 不改它们做的任何事。
 */
final class DigWatch {

    /** 一格的挖掘。 */
    static final class Dig {
        final BlockPos pos;
        BlockState state;
        long startedAt;
        long brokeAt = -1;
        ItemStack tool = ItemStack.EMPTY;
        ItemStack toolAtBreak = ItemStack.EMPTY;
        boolean eyeInWater;
        boolean grounded;
        float progress;
        int starts;
        /** 开挖那一刻正上方那一格,以及东南西北四个邻格。 */
        BlockState above;
        List<BlockState> sides = List.of();
        /** 开挖之后有几刻脚没着地、眼睛泡在水里(原版这几刻挖得慢)。 */
        int airborne;
        int soaked;
        /** 开挖之后有几刻手没按下去(挖掘循环没被调用)。 */
        int skipped;
        long lastCall;
        /** 开挖时手上那一叠本身:原版挖着挖着手上换了一样东西(捡起的东西塞进空着的手)就从头挖。 */
        ItemStack hand = ItemStack.EMPTY;

        Dig(BlockPos pos) {
            this.pos = pos;
        }

        /** 开挖那一刻正上方与四个邻格里有没有这样的方块。 */
        boolean touched(java.util.function.Predicate<BlockState> which) {
            return which.test(above) || sides.stream().anyMatch(which);
        }

        /** 从开挖到碎用了几刻;没碎为 -1。 */
        long ticks() {
            return brokeAt < 0 ? -1 : brokeAt - startedAt;
        }

        /** 原版照开挖那一刻的进度要几刻挖碎。 */
        int vanillaTicks() {
            return (int) Math.ceil(1.0 / progress);
        }

        @Override
        public String toString() {
            return "Dig[" + pos.toShortString() + " " + state + " ticks=" + ticks() + " vanilla=" + vanillaTicks()
                    + " starts=" + starts + " tool=" + tool + " water=" + eyeInWater + " ground=" + grounded
                    + " airborne=" + airborne + " soaked=" + soaked + " skipped=" + skipped + "]";
        }
    }

    private final TestBody body;
    private final Map<BlockPos, Dig> digs = new LinkedHashMap<>();
    /** 手正按在哪一格上;没按为 null。 */
    private BlockPos current;

    DigWatch(TestBody body) {
        this.body = body;
    }

    /** 手此刻正按在某一格上。 */
    boolean digging() {
        return current != null;
    }

    /** 挖碎了的格,按先后。 */
    List<Dig> broken() {
        List<Dig> out = new ArrayList<>();
        for (Dig d : digs.values()) {
            if (d.brokeAt >= 0) {
                out.add(d);
            }
        }
        return out;
    }

    /** 每一格都只开挖了一次:没有中途换靶、没有被当成换了工具重开。 */
    void eachStartedOnce() {
        for (Dig d : digs.values()) {
            if (d.starts != 1) {
                throw new GameTestAssertException("这一格开挖了 " + d.starts + " 次:" + d);
            }
        }
    }

    /** 包在 {@code hands} 外面。 */
    Effector wrap(Effector hands) {
        PlayerHands inner = (PlayerHands) hands;
        return new Effector() {
            @Override
            public Strike dig(BlockHitResult hit) {
                BlockPos pos = hit.getBlockPos().immutable();
                BlockState state = body.level().getBlockState(pos);
                float progress = state.getDestroyProgress(body, body.level(), pos);
                ItemStack hand = body.getMainHandItem();
                ItemStack tool = hand.copy();
                boolean eyeInWater = body.isEyeInFluid(FluidTags.WATER);
                boolean grounded = body.onGround();
                Strike strike = inner.dig(hit);
                long now = body.level().getGameTime();
                Dig ongoing = pos.equals(current) ? digs.get(pos) : null;
                if (ongoing != null) {
                    ongoing.skipped += (int) Math.max(0, now - ongoing.lastCall - 1);
                    ongoing.lastCall = now;
                    ongoing.airborne += grounded ? 0 : 1;
                    ongoing.soaked += eyeInWater ? 1 : 0;
                }
                // 手换到了这一格上开始累加进度,或秒破的当场碎了
                BlockPos pressing = inner.pressing();
                boolean instant = strike instanceof Strike.Broke broke && broke.pos().equals(pos) && !pos.equals(current);
                boolean restarted = ongoing != null && pos.equals(pressing)
                        && !ItemStack.isSameItemSameComponents(hand, ongoing.hand);
                boolean started = instant || restarted || pos.equals(pressing) && !pos.equals(current);
                current = pressing;
                if (started) {
                    Dig d = digs.computeIfAbsent(pos, Dig::new);
                    d.starts++;
                    d.startedAt = now;
                    d.lastCall = now;
                    d.state = state;
                    d.progress = progress;
                    d.tool = tool;
                    d.hand = hand;
                    d.eyeInWater = eyeInWater;
                    d.grounded = grounded;
                    d.above = body.level().getBlockState(pos.above());
                    List<BlockState> sides = new ArrayList<>();
                    for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        sides.add(body.level().getBlockState(pos.relative(side)));
                    }
                    d.sides = sides;
                }
                if (strike instanceof Strike.Broke broke) {
                    Dig d = digs.computeIfAbsent(broke.pos(), Dig::new);
                    d.brokeAt = now;
                    d.toolAtBreak = body.getMainHandItem().copy();
                }
                return strike;
            }

            @Override
            public void release() {
                inner.release();
                current = inner.pressing();
            }

            @Override
            public Use use(BlockHitResult hit) {
                return inner.use(hit);
            }
        };
    }
}
