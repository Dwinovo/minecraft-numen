package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code sleep} 的业务半边:<b>上床,然后确认她真的睡着了</b>。
 *
 * <h2>为什么它这么薄</h2>
 * 找床归 {@code scan_blocks}(现在能写 {@code #minecraft:beds},一句话覆盖全部颜色),
 * 走过去归 {@code goto}——那两件事今天已经各有一个统一的实现,再包一份进来就是第三个入口。
 * 这里只做别处做不了的那一件:<b>把原版的睡眠判定翻译成模型能接住的回执</b>。
 *
 * <h2>三态,不是两态</h2>
 * {@code startSleepInBed} 返回 {@code Either<Problem, Unit>},而 {@code right} 只意味着
 * "没被拒绝"——它不等于"睡着了"。真判据是 {@code isSleeping()}。所以中间那一态必须存在:
 * <b>请求被接受了,但她并没有进入睡眠状态</b>。少了它,一次右键成功就会被当成睡着。
 *
 * <p>被拒绝时的措辞直接用原版自己的({@code BedSleepingProblem.message()})——白天、床被占、
 * 附近有怪、离得太远,一句都不用我们编,而且跟着语言文件走。
 */
public final class SleepOps {

    /** 原版判"够不够得着床"的盒子({@code Player.startSleepInBed} 用的就是这个)。 */
    private static final int REACH_H = 3;
    private static final int REACH_V = 2;

    public String sleep(Integer x, Integer y, Integer z, NumenPlayer self) {
        BlockPos bedHead = x != null && y != null && z != null
                ? headOf(self.level(), new BlockPos(x, y, z))
                : nearestBedHeadInReach(self);
        if (bedHead == null) {
            return noBed(self, x != null && y != null && z != null);
        }

        Either<Player.BedSleepingProblem, Unit> result = self.startSleepInBed(bedHead);
        Player.BedSleepingProblem rejection = result.left().orElse(null);

        // 这三条的顺序就是判据本身:先看原版拒没拒,再看她是不是真躺下了。
        if (rejection != null) {
            return TaskResult.fail(explain(rejection) + " (bed at " + pretty(bedHead) + ")",
                    data(bedHead, false)).toJson();
        }
        if (!self.isSleeping()) {
            return TaskResult.fail(
                    "the sleep request was accepted but you did not actually enter sleep — "
                            + "something cancelled it in the same tick; look around before assuming you rested",
                    data(bedHead, false)).toJson();
        }
        return TaskResult.ok(
                "you are in the bed at " + pretty(bedHead) + " and the server confirms you are sleeping. "
                        + "Night passes on its own; you do not need to wait on a tool for that.",
                data(bedHead, true)).toJson();
    }

    /**
     * 拒绝理由<b>用原版自己的话</b>——它已经足够具体("there are monsters nearby"、
     * "the bed is too far away"、"you can only sleep at night and during thunderstorms"),
     * 我们再翻一遍只会得到一份要跟着原版措辞走的硬编码。而且它跟着语言文件,主人也看得懂。
     *
     * <p>{@code NOT_POSSIBLE_HERE} 与 {@code OTHER_PROBLEM} 原版没配文案({@code getMessage()}
     * 是 {@code null},那两种它在别处另行处理),那时枚举名就是全部信息——不能因为 null 就炸,
     * 那会把"她睡不着"变成"工具报错",而模型对后者无从下手。
     */
    static String explain(Player.BedSleepingProblem problem) {
        var vanilla = problem.getMessage();
        return vanilla != null
                ? vanilla.getString()
                : "the bed refused you (" + problem.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }

    /** 手边够得着的床(原版口径:床的任一半在 ±3/±2/±3 内),归一到床头。 */
    private static BlockPos nearestBedHeadInReach(NumenPlayer self) {
        Level level = self.level();
        BlockPos me = self.blockPosition();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                me.offset(-REACH_H, -REACH_V, -REACH_H),
                me.offset(REACH_H, REACH_V, REACH_H))) {
            BlockPos head = headOf(level, p);
            if (head == null) {
                continue;
            }
            double d = head.distSqr(me);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = head;
            }
        }
        return best;
    }

    /**
     * 这一格是床的话,给出它的<b>床头</b>;不是床则 null。
     *
     * <p>床占两格而 {@code startSleepInBed} 只认床头,所以从任一半算得出来——省得模型
     * 撞运气给了床尾那格。
     */
    private static BlockPos headOf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return null;   // 认床看类型不看 id:模组的床自动算数,不用列 16 种颜色
        }
        return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? pos.immutable()
                : pos.relative(state.getValue(BedBlock.FACING)).immutable();
    }

    /** 没床时把下一步递到她面前——包括"你自己身上就带着一张"。 */
    private String noBed(NumenPlayer self, boolean coordsGiven) {
        String carried = carriedBed(self);
        String base = coordsGiven
                ? "there is no bed at those coordinates"
                : "there is no bed within reach (you must be standing next to one)";
        String next = carried != null
                ? " You are carrying " + carried + " — place it on flat ground and try again."
                : " Use scan_blocks with #minecraft:beds to find one, goto it, then call sleep again.";
        return TaskResult.fail(base + "." + next).toJson();
    }

    /** 背包里的第一张床;没有则 null。 */
    private static String carriedBed(NumenPlayer self) {
        var inv = self.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem block
                    && block.getBlock() instanceof BedBlock) {
                return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                        + " x" + stack.getCount();
            }
        }
        return null;
    }

    private static Map<String, Object> data(BlockPos bed, boolean sleeping) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bed", Map.of("x", bed.getX(), "y", bed.getY(), "z", bed.getZ()));
        out.put("sleeping", sleeping);
        return out;
    }

    private static String pretty(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
