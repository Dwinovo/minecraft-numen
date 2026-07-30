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

    private static final String SCAFFOLD_RECLAIM_ROUTE = """


<temporary_scaffold_reclaim_routing>
- A request to reclaim temporary scaffolds, including temporary support blocks,
  pillaring blocks, navigation steps, or temporary bridges, MUST call
  reclaim_temporary_scaffolds directly.
- Never call mine for temporary scaffold cleanup. mine searches by block type and
  must not touch unrelated terrain or construction; the reclaim tool operates only
  on exact coordinates recorded in the companion's temporary-scaffold ledger.
- The reclaim task rechecks current path use, support, landing hazards, fall safety,
  and reach before each removal. Wait for task_finished, then report what was
  reclaimed and every block that was retained with its stated reason.
</temporary_scaffold_reclaim_routing>
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

    private static final String SLEEP_ROUTE = """


<sleep_task_routing>
- When the primary goal is sleep, rest, or getting into a bed, call sleep directly.
  This includes compound requests such as "come sleep" or "come to bed": the
  movement word does not replace the requested sleep outcome, so you must not
  call follow_owner for those requests.
- Treat Chinese and romanized sleep intent the same way. Common romanized forms
  include laishuijiao, lai shuijiao, qushuijiao, and qu shuijiao.
- Do not assemble sleep manually from scan_blocks, goto, and interact_at. The
  sleep task finds a nearby bed, travels to it, asks the vanilla server to sleep,
  and reports the verified outcome.
- Never use interact_at on a bed to satisfy a sleep request. The initial accepted
  reply is not sleep success; wait for task_finished(status=done) and the verified
  sleeping result before saying that sleep completed.
- Claim that you slept only when sleep reports success. If it reports daytime,
  obstruction, danger, occupancy, no nearby bed, or another failure, state that
  reason accurately instead of treating a right-click as successful sleep.
</sleep_task_routing>
""";

    private static final String CONSUMABLE_ROUTE = """


<survival_consumable_routing>
- For a generic request to eat, inspect current status and inventory, then choose
  ordinary safe food. Use a golden carrot only when no ordinary safe food is available.
- Risky food is a last famine fallback only when hunger is 6 or lower and no safe
  food is available. Otherwise report that no suitable food is currently available.
- Do not use a golden apple, enchanted golden apple, or healing potion for ordinary
  hunger. It is eligible only when the active survival-healing conditions require it
  and the recovery policy selects it. Naming the exact item does not override this
  safety rule. Never choose one merely because it is the rarest, strongest, or "best".
- Live inventory labels identify safe healing potions precisely:
  minecraft:potion[instant_health] heals immediately and
  minecraft:potion[regeneration] restores health over time. When health is low and
  the recovery policy selects one, use eat_item; it resolves the exact healing stack.
- Never use eat_item for splash_potion or lingering_potion, and never drink a healing
  potion while healthy merely because it is present in the inventory.
- A milk bucket clears harmful and beneficial effects together. Automatic recovery
  uses it only when the recovery policy selects a safe cleanse. If the owner explicitly
  asks you to drink milk, obey that request even when no harmful effect is active.
- A totem of undying is not food. The emergency reflex automatically equips it in the
  offhand only at immediate death risk and restores the previous offhand after danger
  passes. Do not call eat_item, drop_items, or manual transfer to simulate totem use.
</survival_consumable_routing>
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
        if (!corrected.contains("<temporary_scaffold_reclaim_routing>")) {
            corrected += SCAFFOLD_RECLAIM_ROUTE;
        }
        if (!corrected.contains("<inventory_task_routing>")) {
            corrected += INVENTORY_ROUTE;
        }
        if (!corrected.contains("<nearby_combat_routing>")) {
            corrected += COMBAT_ROUTE;
        }
        if (!corrected.contains("<sleep_task_routing>")) {
            corrected += SLEEP_ROUTE;
        }
        if (!corrected.contains("<survival_consumable_routing>")) {
            corrected += CONSUMABLE_ROUTE;
        }
        return corrected;
    }
}
