package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.core.init.InitTag;

import net.minecraft.server.level.ServerPlayer;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 垫路料清单:解析与归一,以及下一次垫路放什么。需要 MC 注册表;出厂清单的标签由测试自己绑上。 */
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

    // ==================== id 解析 ====================

    @Test
    void aBareNameMeansTheVanillaBlock() {
        assertEquals(Items.COBBLESTONE, ThrowawayBlocks.parse("cobblestone"));
        assertEquals(Items.COBBLESTONE, ThrowawayBlocks.parse("minecraft:cobblestone"));
    }

    @Test
    void surroundingSpaceAndCaseAreForgiven() {
        assertEquals(Items.DEEPSLATE, ThrowawayBlocks.parse("  Minecraft:DeepSlate  "));
    }

    @Test
    void somethingThatIsNotABlockIdIsRejectedRatherThanGuessed() {
        assertNull(ThrowawayBlocks.parse("definitely_not_a_block"));
        assertNull(ThrowawayBlocks.parse("not a resource location"));
        assertNull(ThrowawayBlocks.parse(""));
        assertNull(ThrowawayBlocks.parse(null));
    }

    // ==================== 归一 ====================

    /** 顺序就是选料优先级,所以归一不能重排。 */
    @Test
    void theGivenOrderIsKeptBecauseItIsThePickingOrder() {
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone"),
                ThrowawayBlocks.normalize(List.of("stone", "dirt", "cobblestone")));
    }

    @Test
    void aRepeatedBlockIsListedOnceAtItsFirstPosition() {
        assertEquals(List.of("minecraft:dirt", "minecraft:stone"),
                ThrowawayBlocks.normalize(
                        List.of("dirt", "minecraft:stone", "dirt", "minecraft:dirt")));
    }

    /** 认不出的悄悄丢掉,调用方回报的是落盘后读回来的那份,模型看得见自己的 id 没生效。 */
    @Test
    void unknownIdsAreDroppedAndTheRestSurvive() {
        assertEquals(List.of("minecraft:dirt"),
                ThrowawayBlocks.normalize(Arrays.asList("nope:whatever", "dirt", null, "")));
    }

    @Test
    void nothingUsableNormalisesToAnEmptyList() {
        assertTrue(ThrowawayBlocks.normalize(List.of("nope:whatever")).isEmpty());
        assertTrue(ThrowawayBlocks.normalize(null).isEmpty());
    }

    // ==================== 出厂默认 ====================

    /**
     * 出厂默认是一条<b>标签引用</b>,不是展开后的清单。这样整合包改
     * {@code numen:throwaway} 就能改掉所有新同伴的起点,而静态常量读不到数据包——
     * 存引用、用时再解析,才躲得开那个时序。清单内容本身由 datagen 那份定义,
     * 它的判据(不含重力方块、不含有功能的方块)在 {@code ModItemTagData} 那边钉。
     */
    @Test
    void theFactoryDefaultIsATagReferenceSoPacksCanChangeIt() {
        assertEquals(List.of("#numen:throwaway"), ThrowawayBlocks.factoryDefaultIds());
    }

    /** 标签引用必须真的解析得开——认不出就等于所有新同伴一件垫路料都没有。 */
    @Test
    void thatReferenceResolvesToRealItems() {
        String ref = ThrowawayBlocks.factoryDefaultIds().get(0);
        assertNotNull(InitTag.parseRef(net.minecraft.core.registries.Registries.ITEM, ref), ref);
    }

    // ==================== 下一次垫什么(端口 Materials 的答案) ====================

    /** 清单的先后就是挑的先后,与料放在背包哪一格无关:出厂清单里泥土排在圆石前面。 */
    @Test
    void theListOrderBeatsTheSlotOrder() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE));
        player.getInventory().items.set(3, new ItemStack(Items.DIRT));
        assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player));
    }

    /** 背包深处的料一样算:她能把它换进快捷栏(换的那一下由寻路交回、记进回执)。 */
    @Test
    void blocksDeepInTheInventoryCount() {
        clear();
        player.getInventory().items.set(30, new ItemStack(Items.COBBLESTONE));
        assertEquals(Optional.of(Blocks.COBBLESTONE), ThrowawayBlocks.next(player));
    }

    /** 只有副手里有料也算:原版右键主手用不了就试副手。 */
    @Test
    void theOffhandCounts() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.STONE_SWORD));
        player.getInventory().offhand.set(0, new ItemStack(Items.DIRT));
        assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player));
    }

    /** 背包里的方块不在清单上就不是料:清单是她自己定的,别的方块留着盖房子。 */
    @Test
    void nothingOnTheListIsNothingToPlace() {
        clear();
        player.getInventory().items.set(0, new ItemStack(Items.OAK_PLANKS));
        assertEquals(Optional.empty(), ThrowawayBlocks.next(player));
    }

    /** 创造模式身上没有料也有:寻路拿到手上时凭空取一叠清单上的第一种。 */
    @Test
    void creativeHasTheFirstBlockOnTheList() {
        clear();
        player.getAbilities().instabuild = true;
        try {
            assertEquals(Optional.of(Blocks.DIRT), ThrowawayBlocks.next(player));
        } finally {
            player.getAbilities().instabuild = false;
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

    // ==================== 夹具:一个只有背包、饱食与能力的空壳玩家 ====================

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
        return p;
    }
}
