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

/** World-action tool (raw NumenTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
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
        return "Equip an item from your OWN inventory: tool/weapon to the main hand, armor and modded "
                + "accessories (Curios/Trinkets) auto-routed to their slots; the previous item is "
                + "stowed back. Or take gear OFF: action=unequip with a slot stows it into the "
                + "inventory ('armor' strips all four pieces, 'mainhand' frees your hand); fails if "
                + "there is no room.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "equip (default): wear/wield item_id. "
                        + "unequip: empty a slot back into the inventory.",
                        "equip", "unequip")
                .optionalString("item_id", "Namespaced id of the item to equip; must be in the "
                        + "inventory. Required to equip, ignored for unequip.")
                .optionalEnum("slot", "equip: omit to auto-route by item type, set only to force a "
                        + "hand or a specific armor piece. unequip: required — the slot to empty; "
                        + "'armor' means all four armor pieces.",
                        "mainhand", "offhand", "head", "chest", "legs", "feet", "armor")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.equipItem(a.action(), a.item_id(), a.slot(), ctx(toolCallId, companion)), reply);
    }
}
