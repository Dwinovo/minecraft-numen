package com.dwinovo.numen.bench;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 场景看到的这一次运行:世界、场地坐标、她、主人。坐标相对场地(见 {@link Scenario}),{@link #pos} 换成世界坐标。
 */
public final class Scene {

    private final ServerLevel level;
    private final BlockPos origin;
    private final NumenPlayer her;
    private final ServerPlayer owner;
    private boolean died;

    Scene(ServerLevel level, BlockPos origin, NumenPlayer her, ServerPlayer owner) {
        this.level = level;
        this.origin = origin;
        this.her = her;
        this.owner = owner;
    }

    public ServerLevel level() {
        return level;
    }

    /** 场地里的一格换成世界坐标。 */
    public BlockPos pos(int x, int y, int z) {
        return origin.offset(x, y, z);
    }

    public BlockPos pos(BlockPos rel) {
        return origin.offset(rel);
    }

    /** 她的身体。 */
    public NumenPlayer her() {
        return her;
    }

    /** 模拟主人,一个在线的玩家。 */
    public ServerPlayer owner() {
        return owner;
    }

    public void set(int x, int y, int z, BlockState state) {
        level.setBlockAndUpdate(pos(x, y, z), state);
    }

    /** 放进她的背包。 */
    public void give(ItemStack stack) {
        her.getInventory().add(stack);
    }

    /** 这一次里她死过。 */
    public boolean died() {
        return died;
    }

    void markDied() {
        died = true;
    }

    /** 断言,写法同 GameTest:不成立就抛出,{@code message} 说看到了什么。 */
    public void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
