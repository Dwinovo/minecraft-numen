package com.dwinovo.numen.task.move;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.world.Kinematics;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 掉在地上的东西"走到旁边"是走到身体捡得到它的地方:原版玩家每刻拿自己的碰撞盒各向外放 {@value #GROW_XZ}(水平)、{@value #GROW_Y}(竖直)
 * 去碰物品的盒子,碰上了就捡。判据与原版是同一个,所以不必估"离它几格":贴着台沿的东西坐标取整落在悬空的格里,那一格站不进去,
 * 可从旁边一格就捡得到。
 *
 * <p>身体不必停在格中心:到了这一格之后朝物品走({@link MoveToCompanionTask} 收场的最后一段),走到碰上物品、或被碰撞挡住为止
 * (玩家、mineflayer-collectblock 的 {@code GoalFollow(物品, 0)}、Baritone 把目标设在掉落物所在处,都是这样让身体往里走)。
 * 被挡住时身体半宽贴墙,位置由几何定死:朝物品偏出格中心 {@link #LEAN}(半格减半个身宽),不靠停点的精度。{@link #contains}
 * 认的就是这一点,所以物品落在只有一格高、头顶是石头的坑里,从坑边那一格就捡得到,不必挖坑顶。
 */
final class ItemReach implements Goal {

    /** 原版捡东西时玩家盒子向外放的量。 */
    static final double GROW_XZ = 1.0;
    static final double GROW_Y = 0.5;
    /** 站在一格里朝一侧走到被挡住,身体偏出格中心的量:半格减半个身宽。 */
    static final double LEAN = 0.5 - EntityType.PLAYER.getWidth() / 2;

    private final AABB item;

    ItemReach(AABB item) {
        this.item = item;
    }

    /** 节点这一格里朝物品走到被挡住那一点({@link #LEAN})捡得到,竖直上按脚底的高度(落脚的格定了高度)。 */
    @Override
    public boolean contains(int x, int y, int z, Stance stance) {
        return touches(x, z, stance.feetY(), stance.feetY());
    }

    /**
     * 脚底在 {@code [feetLo, feetHi]} 之间时,{@code (x, z)} 这一列的格里捡不捡得到:{@link #contains} 与 {@link #endCells}
     * 共用这一个判据,前者给确切的脚底,后者给"脚在一格里任何高度"。
     */
    private boolean touches(int x, int z, double feetLo, double feetHi) {
        return gap(x, item.getCenter().x) <= reachX() && gap(z, item.getCenter().z) <= reachZ()
                && feetLo - GROW_Y <= item.maxY && feetHi + EntityType.PLAYER.getHeight() + GROW_Y >= item.minY;
    }

    /** 这一列的格(坐标 {@code cell})里,朝物品走到头,身体中心离物品中心还剩多远。 */
    private static double gap(int cell, double center) {
        return Math.max(0, Math.abs(cell + 0.5 - center) - LEAN);
    }

    /** 身体的盒子此刻碰得上物品:原版每刻捡东西的判据。 */
    boolean reaches(AABB body) {
        return body.inflate(GROW_XZ, GROW_Y, GROW_XZ).intersects(item);
    }

    /** 物品的中心:朝它走。 */
    Vec3 center() {
        return item.getCenter();
    }

    /** 脚在一格里任何高度时捡得到的格:水平、竖直各枚举到判据外一格为止,再逐格问 {@link #touches}。 */
    @Override
    public LongSet endCells() {
        LongSet cells = new LongOpenHashSet();
        for (int x = (int) Math.floor(item.getCenter().x - reachX() - LEAN); x <= Math.ceil(item.getCenter().x + reachX() + LEAN); x++) {
            for (int z = (int) Math.floor(item.getCenter().z - reachZ() - LEAN); z <= Math.ceil(item.getCenter().z + reachZ() + LEAN); z++) {
                for (int y = (int) Math.floor(item.minY - EntityType.PLAYER.getHeight() - GROW_Y) - 1; y <= Math.ceil(item.maxY + GROW_Y); y++) {
                    if (touches(x, z, y, y + 1)) {
                        cells.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    @Override
    public Heights heights() {
        return Goal.heightsOf(endCells());
    }

    /** 水平底价:节点中心离"能算到了的那一圈"的水平距离乘疾跑一格;欧氏距离不超过任何走法的格数。 */
    @Override
    public double estimate(int x, int y, int z, Kinematics.Pace pace) {
        double dx = Math.max(0, Math.abs(x + 0.5 - item.getCenter().x) - (reachX() + LEAN));
        double dz = Math.max(0, Math.abs(z + 0.5 - item.getCenter().z) - (reachZ() + LEAN));
        return pace.sprint() * Math.hypot(dx, dz);
    }

    /** 水平上物品盒的中心到身体中心多远以内碰得上:身体半宽加向外放的量加物品半宽。 */
    private double reachX() {
        return EntityType.PLAYER.getWidth() / 2 + GROW_XZ + item.getXsize() / 2;
    }

    private double reachZ() {
        return EntityType.PLAYER.getWidth() / 2 + GROW_XZ + item.getZsize() / 2;
    }

    @Override
    public String toString() {
        return "pickUp(" + item + ")";
    }
}
