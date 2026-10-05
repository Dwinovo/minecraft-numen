package com.dwinovo.numen.core.act;

import com.dwinovo.numen.entity.BodyDelta;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次按键前后的世界事实差异。按键被"消费"不等于"发生了什么"——船可以吃掉点击,
 * 却因为生成位置和身体重叠被原版静默拒绝,世界纹丝不动。这里不判成败,只把看得见的变化如实报出:
 * 她身上的(背包与经验、血量、骑乘,见 {@link BodyDelta})、瞄着的那一格、身边新冒出来的实体。
 * 判断交给读回执的人——什么算"成"只有意图知道,而意图在模型那边。
 */
public final class PressReceipt {

    /** 新实体的观察半径:够罩住触及距离(4.5)内放出的船/掷物,不把远处的路人算进来。 */
    private static final double ENTITY_RADIUS = 6.0;

    private final BodyDelta body;
    private final BlockPos aim;
    private final BlockState aimBefore;
    private final Set<Integer> entityIdsBefore;

    private PressReceipt(NumenPlayer player, BlockPos aim) {
        this.body = BodyDelta.open(player);
        this.aim = aim;
        this.aimBefore = aim == null ? null : player.level().getBlockState(aim);
        this.entityIdsBefore = nearbyIds(player);
    }

    /** 按键之前拍快照;{@code aim} 可空(朝空气挥没有目标格)。 */
    public static PressReceipt before(NumenPlayer player, BlockPos aim) {
        return new PressReceipt(player, aim);
    }

    /**
     * 按键之后对账:每一条是一件真发生的事,空表 = 观察的几个面都没动静。
     * 语言面向工具回执(英文),坐标点名,方便模型下一步引用。
     */
    public List<String> diff(NumenPlayer player) {
        List<String> facts = new ArrayList<>();
        facts.addAll(body.changes(player));
        if (aim != null) {
            BlockState now = player.level().getBlockState(aim);
            if (now != aimBefore) {
                facts.add("block at " + aim.getX() + "," + aim.getY() + "," + aim.getZ()
                        + ": " + blockName(aimBefore) + " -> " + blockName(now));
            }
        }
        for (Entity e : nearby(player)) {
            if (!entityIdsBefore.contains(e.getId())) {
                facts.add("appeared: " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()
                        + " (id " + e.getId() + ")");
            }
        }
        return facts;
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static Set<Integer> nearbyIds(NumenPlayer player) {
        Set<Integer> ids = new HashSet<>();
        for (Entity e : nearby(player)) {
            ids.add(e.getId());
        }
        return ids;
    }

    private static List<Entity> nearby(NumenPlayer player) {
        return player.level().getEntities(player,
                player.getBoundingBox().inflate(ENTITY_RADIUS));
    }
}
