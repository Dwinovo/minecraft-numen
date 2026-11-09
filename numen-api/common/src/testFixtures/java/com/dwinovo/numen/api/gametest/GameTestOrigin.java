package com.dwinovo.numen.api.gametest;

import net.minecraft.core.BlockPos;

/**
 * 全部场地铺开的起点:每次开服都是同一处,一次失败换一次开服还在原处,能原样重放。
 *
 * <p>这一代原版 {@code GameTestServer.startTests} 把起点写死在 {@code (0, -60, 0)},就在出生区块上(后来的版本改成不定种子的
 * 随机一处)。我们的用例有随起点变的东西(场地落在区块里的哪一格、以起点区块为中心的寻路快照、远处的落点与下界的地形),起点一变结论就可能跟着变,
 * 所以两个模组加载器都钉在这里给的一处(经 {@code GameTestServerOriginMixin})。
 *
 * <p>默认 {@link #DEFAULT}:离出生点远,不碰出生区块,贴着超平坦的地面(与原版取的 y 相同)。系统属性 {@value #PROPERTY}
 * ({@code -PgametestOrigin=x,y,z})换一处,用来在别处重放或查一条结论随不随起点变。
 */
public final class GameTestOrigin {

    /** 指定起点的系统属性:{@code x,y,z}。 */
    public static final String PROPERTY = "numen.gametest.origin";
    /** 没指定时的起点。 */
    public static final BlockPos DEFAULT = new BlockPos(4096, -60, 4096);

    private GameTestOrigin() {}

    /** 这次开服的起点。 */
    public static BlockPos get() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] parts = value.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException(PROPERTY + " 要写成 x,y,z:" + value);
        }
        return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }

    /** 失败日志里写的那一段:起点与世界种子,照着就能重放。 */
    public static String describe(long seed) {
        BlockPos origin = get();
        return "origin " + origin.getX() + "," + origin.getY() + "," + origin.getZ() + " seed " + seed;
    }
}
