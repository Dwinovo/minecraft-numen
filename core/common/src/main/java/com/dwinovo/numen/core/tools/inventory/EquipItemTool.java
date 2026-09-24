package com.dwinovo.numen.core.tools.inventory;
import com.dwinovo.numen.core.tools.InventoryOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): wear, hold or take off gear. Translates arguments only; the work is {@link com.dwinovo.numen.core.gear.Wardrobe}. */
public final class EquipItemTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String action, String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        // 固定文字:槽名清单每轮随 <worn> 下发,不写进这里,工具表才字节稳定
        return "Wear or hold an item from your OWN backpack, or take gear off. equip (default): armor "
                + "and accessories go to a free slot that takes them (swapping out what was there), a "
                + "shield to the off hand, anything else to the main hand; the swapped-out item goes back "
                + "into the backpack. unequip: stows what a slot holds back into the backpack. Your "
                + "wearable slots and what is on them are listed in <worn>. Fails without changing "
                + "anything when the slot refuses the item or the backpack has no room.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "equip (default): wear/hold item_id. "
                        + "unequip: take gear off back into the backpack.",
                        "equip", "unequip")
                .optionalString("item_id", "Namespaced item id. equip: required, must be in your "
                        + "backpack. unequip: take off the piece you wear that is this item.")
                .optionalString("slot", "equip: omit to choose automatically, or force mainhand, "
                        + "offhand or a slot name from <worn>. unequip: mainhand, offhand, a slot name "
                        + "from <worn>, or 'armor' for all four armor pieces; omit it to go by item_id.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.equipItem(a.action(), a.item_id(), a.slot(), ctx(toolCallId, companion)), reply);
    }
}
