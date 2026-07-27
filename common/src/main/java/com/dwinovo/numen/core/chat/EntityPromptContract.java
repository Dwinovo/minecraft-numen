package com.dwinovo.numen.core.chat;

/** Keeps the entity prompt aligned with the tools actually registered at runtime. */
public final class EntityPromptContract {
    private static final String MINING_ROUTE = """


<block_task_routing>
- An explicit request to mine, gather, harvest, or cut blocks MUST use mine to
  perform the extraction. Never substitute goto plus interact_at for mining.
- scan_blocks may be used only when target availability or location is genuinely
  unknown. After a scan finds the requested blocks, call mine; do not manually
  walk the returned coordinates and interact with them one by one.
- Give mine every requested block variant and the requested item count. Let its
  background task navigate, scaffold, mine, verify completion, and report each
  impossible target. Use goto or interact_at only for a different explicit goal
  or after mine returns a concrete failure that specifically requires one.
</block_task_routing>
""";

    private static final String INVENTORY_ROUTE = """


<inventory_task_routing>
- A plain-language request to remove armor means: close any external GUI, call
  inspect_gui on your own InventoryMenu, then remove the named head, chest,
  legs, and feet slots into empty backpack slots 9-35.
- Use transfer for every armor move so the complete ItemStack, including all
  enchantments, durability, and components, is preserved. Never use drop_items
  for unequipping, and never put armor on the ground.
- After removing armor, call inspect_gui again and verify all four named armor
  slots are empty. Then do not call equip_item again until the owner explicitly asks.
- A plain-language request to empty hands means: call task_status first and use
  task_stop if a task is active, close any external GUI, then call inspect_gui.
  Use transfer to move the currently selected mainhand slot and the named offhand slot
  into empty backpack slots 9-35 without dropping either stack.
- Never guess a hotbar slot: inspect_gui identifies the current selection. Verify
  both named hand slots are empty, keep that mainhand slot selected, and do not
  switch to another occupied hotbar slot merely to appear empty.
- If transfer refuses a move or no empty backpack slot exists, stop and report
  the exact reason. Never drop, overwrite, retry forever, or claim that the slot is empty.
</inventory_task_routing>
""";

    private static final String COMBAT_ROUTE = """


<nearby_combat_routing>
- For an ordinary request to clear nearby monsters, call scan_nearby_entities
  with origin=self. Use origin=owner only when the user explicitly says around
  the owner/player, such as "around me" or "near the player".
- Use level_scope=same_plane by default. Use level_scope=all only when the user
  explicitly requests targets at other heights, above and below, or throughout
  the full nearby vertical range. A plane is the fixed Minecraft Y-axis band
  captured by the scan; it does not mean a literal building floor.
- Nearby discovery is a bounded cylindrical range scan, not camera direction.
  Pass only the returned runtime ids to melee_attack or ranged_attack; line of
  sight remains an attack-time safety check and must not narrow initial discovery.
</nearby_combat_routing>
""";

    private EntityPromptContract() {
    }

    public static String apply(String prompt) {
        String corrected = prompt == null ? "" : prompt
            .replace("auto_mine", "mine")
            .replace("move_to", "goto");
        if (!corrected.contains("<block_task_routing>")) {
            corrected += MINING_ROUTE;
        }
        if (!corrected.contains("<inventory_task_routing>")) {
            corrected += INVENTORY_ROUTE;
        }
        if (!corrected.contains("<nearby_combat_routing>")) {
            corrected += COMBAT_ROUTE;
        }
        return corrected;
    }
}
