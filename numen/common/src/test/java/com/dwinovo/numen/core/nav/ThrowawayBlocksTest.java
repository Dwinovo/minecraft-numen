package com.dwinovo.numen.core.nav;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 垫路料:描述里写的料怎么读、出厂那一份,以及下一次垫路放什么。需要 MC 注册表;出厂标签由测试自己绑上。 */
@Tag("mc")
class ThrowawayBlocksTest {

    private static ServerPlayer player;

    @BeforeAll
    static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        com.dwinovo.numen.core.ThrowawayTagTestSupport.bind();
        player = allocatePlayer();
    }

    // ==================== 描述里写的料 ====================

    @Test
    void aBareNameMeansTheVanillaBlock() {
        assertEquals(List.of(Items.COBBLESTONE), ThrowawayBlocks.of(List.of("cobblestone")));
        assertEquals(List.of(Items.COBBLESTONE), ThrowawayBlocks.of(List.of("minecraft:cobblestone")));
    }

    @Test
    void surroundingSpaceAndCaseAreForgiven() {
        assertEquals(List.of(Items.DEEPSLATE), ThrowawayBlocks.of(List.of("  Minecraft:DeepSlate  ")));
    }

    /** 顺序就是选料优先级,所以不重排;重复的只留第一次出现的位置。 */
    @Test
    void theGivenOrderIsKeptAndARepeatIsListedOnce() {
        assertEquals(List.of(Items.STONE, Items.DIRT, Items.COBBLESTONE),
                ThrowawayBlocks.of(List.of("stone", "dirt", "cobblestone")));
        assertEquals(List.of(Items.DIRT, Items.STONE),
                ThrowawayBlocks.of(List.of("dirt", "minecraft:stone", "dirt", "minecraft:dirt")));
    }

    /** 认不出的、不是能放下的方块是描述写错了:说是哪一项,不悄悄丢掉。 */
    @Test
    void somethingThatIsNotABlockToPlaceIsRefusedRatherThanDropped() {
        assertThrows(IllegalArgumentException.class, () -> ThrowawayBlocks.of(List.of("definitely_not_a_block")));
        assertThrows(IllegalArgumentException.class, () -> ThrowawayBlocks.of(List.of("not a resource location")));
        assertThrows(IllegalArgumentException.class, () -> ThrowawayBlocks.of(List.of("minecraft:diamond")));
        assertThrows(IllegalArgumentException.class, () -> ThrowawayBlocks.of(List.of("#minecraft:no_such_tag")));
    }

    // ==================== 出厂那一份 ====================

    /** 出厂那一份是标签 numen:throwaway 此刻的成员:整合包改标签就改掉所有同伴的出厂料;标签要真的解析得开。 */
    @Test
    void theFactoryListIsTheThrowawayTag() {
        assertTrue(ThrowawayBlocks.factory().containsAll(List.of(Items.DIRT, Items.COBBLESTONE)),
                "" + ThrowawayBlocks.factory());
        assertEquals(ThrowawayBlocks.factory(), ThrowawayBlocks.of(List.of("#numen:throwaway")));
    }

    // ==================== 下一次垫什么(端口 Materials 的答案) ====================

    /** 清单的先后就是挑的先后,与料放在背包哪一格无关:出厂清单里泥土排在圆石前面。 */
    @Test
    void theListOrderBeatsTheSlotOrder() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE));
        player.getInventory().items.set(3, new ItemStack(Items.DIRT));
        assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player, ThrowawayBlocks.factory()));
    }

    /** 背包深处的料一样算:她能把它换进快捷栏(换的那一下由寻路交回、记进回执)。 */
    @Test
    void blocksDeepInTheInventoryCount() {
        clear();
        player.getInventory().items.set(30, new ItemStack(Items.COBBLESTONE));
        assertEquals(Optional.of(Blocks.COBBLESTONE), ThrowawayBlocks.next(player, ThrowawayBlocks.factory()));
    }

    /** 只有副手里有料也算:原版右键主手用不了就试副手。 */
    @Test
    void theOffhandCounts() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.STONE_SWORD));
        player.getInventory().offhand.set(0, new ItemStack(Items.DIRT));
        assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player, ThrowawayBlocks.factory()));
    }

    /** 背包里的方块不在这一趟的料里就不是料:别的方块留着盖房子。 */
    @Test
    void nothingOnTheListIsNothingToPlace() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.OAK_PLANKS));
        assertEquals(Optional.empty(), ThrowawayBlocks.next(player, ThrowawayBlocks.factory()));
    }

    /** 创造模式身上没有料也有:寻路拿到手上时凭空取一叠清单上的第一种。 */
    @Test
    void creativeHasTheFirstBlockOnTheList() {
        clear();
        mode(GameType.CREATIVE);
        try {
            assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player, ThrowawayBlocks.factory()));
        } finally {
            mode(GameType.SURVIVAL);
        }
    }

    private static void clear() {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            inv.items.set(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < inv.offhand.size(); i++) {
            inv.offhand.set(i, ItemStack.EMPTY);
        }
    }

    /** 把空壳玩家的游戏模式直接记成 {@code mode}(原版的切换要发包,空壳没有连接)。 */
    private static void mode(GameType mode) {
        try {
            Field field = ServerPlayerGameMode.class.getDeclaredField("gameModeForPlayer");
            field.setAccessible(true);
            field.set(player.gameMode, mode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 夹具:一个只有背包、饱食、能力与游戏模式的空壳玩家 ====================

    private static ServerPlayer allocatePlayer() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        ServerPlayer p = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(p, new Inventory(p));
        Field abilities = Player.class.getDeclaredField("abilities");
        abilities.setAccessible(true);
        abilities.set(p, new Abilities());   // 默认生存画像
        Field gameMode = ServerPlayer.class.getDeclaredField("gameMode");
        gameMode.setAccessible(true);
        gameMode.set(p, new ServerPlayerGameMode(p));   // 默认生存模式
        return p;
    }
}
