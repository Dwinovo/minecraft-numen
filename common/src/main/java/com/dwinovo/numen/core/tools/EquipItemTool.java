package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
public final class EquipItemTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory: tool/weapon to the main hand, armor and modded "
                + "accessories (Curios/Trinkets) auto-routed to their slots. Omit slot for auto-routing; "
                + "set it only to force a hand or a specific armor piece. The previous item is stowed back. "
                + "Explicit equipping PINS the slot — automatic gear swaps won't touch it until the item "
                + "breaks or leaves; release a pin with item_id \"auto\" plus the slot.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to equip; must be in the inventory. "
                        + "The special value \"auto\" equips nothing and instead releases the given "
                        + "slot's pin, handing the slot back to your reflexes (slot required).")
                .optionalEnum("slot", "Optional target slot; omit to auto-route by item type. "
                        + "Required when item_id is \"auto\".",
                        "mainhand", "offhand", "head", "chest", "legs", "feet")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.equipItem(a.item_id(), a.slot(), ctx(toolCallId, companion)));
    }
}
