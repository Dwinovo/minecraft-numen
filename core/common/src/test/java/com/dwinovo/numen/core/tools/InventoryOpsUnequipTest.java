package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.gear.Wardrobe;
import com.dwinovo.numen.core.task.inventory.UnequipTaskRecord;
import com.dwinovo.numen.task.TaskRecord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code equip_item} 双形态的参数路由钉:action=unequip 走 {@link UnequipTaskRecord},
 * 各形态的必填约束在 schema 层是宽的(BlueprintTool 同款 action 模式),真正的闸在这里
 * ——所以闸的行为要钉死。槽名随身体而定,记录里存的是原样的名字,展开与解析在执行时由 Wardrobe 做。
 * 纯 JVM:只走 unequip 与拒绝路径,不触碰物品注册表。
 */
class InventoryOpsUnequipTest {

    private final InventoryOps ops = new InventoryOps();
    private final ToolContext ctx = new ToolContext("call-1", 0L);

    @Test
    void armorExpandsToAllFourPieces() {
        TaskRecord rec = ops.equipItem("unequip", null, "armor", ctx);
        UnequipTaskRecord un = assertInstanceOf(UnequipTaskRecord.class, rec);
        assertEquals("armor", un.slot);
        assertEquals(List.of("head", "chest", "legs", "feet"), Wardrobe.namesFor(un.slot));
    }

    @Test
    void singleSlotUnequips() {
        UnequipTaskRecord un = assertInstanceOf(UnequipTaskRecord.class,
                ops.equipItem("unequip", null, "mainhand", ctx));
        assertEquals("mainhand", un.slot);
        assertEquals(List.of("mainhand"), Wardrobe.namesFor(un.slot));
    }

    @Test
    void unequipWithoutSlotIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ops.equipItem("unequip", null, null, ctx));
    }

    @Test
    void equipWithoutItemIsRefused() {
        // action 缺省即 equip——item_id 在 schema 层是可选的,闸在这里
        assertThrows(IllegalArgumentException.class,
                () -> ops.equipItem(null, null, "head", ctx));
    }

    @Test
    void armorSlotIsUnequipOnly() {
        // 穿戴没有"armor"这个目标:四件甲各回各槽,批量语义只属于脱
        assertThrows(IllegalArgumentException.class,
                () -> ops.equipItem(null, "minecraft:iron_helmet", "armor", ctx));
    }
}
