package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Block-action tools authored on the {@link NumenAction} surface — the
 * world-action dogfood for construction and native interaction. Each method
 * validates its args and builds a {@link TaskRecord}; the {@link ToolContext}
 * carries the call id and deadline basis. Behaviour matches the hand-written
 * {@code NumenTool} classes they replace.
 */
public final class BlockActionTools {

    // mine budgets / bounds.
    private static final int MAX_COUNT = 256;
    /** Per-block budget is generous; total scales with count so big jobs don't time out. */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    // interact_at: covers walking to the aim.
    private static final long INTERACT_AT_TIMEOUT_TICKS = 30 * 20;
    // interact_entity: covers chasing a moving target.
    private static final long INTERACT_ENTITY_TIMEOUT_TICKS = 60 * 20;

    public TaskRecord autoMine(List<String> block_ids, int count, ToolContext ctx) {
        Set<Block> targets = readBlockIds(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        int clampedCount = Math.clamp(count, 1, MAX_COUNT);
        String label = labelFor(targets);
        long timeout = Math.max(MIN_TIMEOUT_TICKS, (long) clampedCount * TICKS_PER_BLOCK);
        long deadline = ctx.deadline(timeout);
        return new MineBlockTaskRecord(ctx.toolCallId(), deadline, targets, clampedCount, label);
    }

    private static Set<Block> readBlockIds(List<String> blockIds) {
        Set<Block> out = new LinkedHashSet<>();
        for (String el : blockIds) {
            if (el == null) continue;
            ResourceLocation id = ResourceLocation.tryParse(el);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.getValue(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    public TaskRecord interactAt(
String button,
Integer x,
Integer y,
Integer z,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        InteractAtTaskRecord.Button buttonVal = readAtButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;

        BlockPos aim = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "an aim point needs all of x, y, z (or leave all null to use the held item straight ahead).");
            }
            aim = new BlockPos(x, y, z);
        }
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_AT_TIMEOUT_TICKS), buttonVal, aim, holdTicks, item);
    }

    private static InteractAtTaskRecord.Button readAtButton(String button) {
        if (button == null) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (button) {
            case "left" -> InteractAtTaskRecord.Button.LEFT;
            case "right" -> InteractAtTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + button);
        };
    }

    public TaskRecord interactEntity(
String button,
int entity_id,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        InteractEntityTaskRecord.Button buttonVal = readEntityButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_ENTITY_TIMEOUT_TICKS), buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id));
    }

    private static InteractEntityTaskRecord.Button readEntityButton(String button) {
        if (button == null) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (button) {
            case "left" -> InteractEntityTaskRecord.Button.LEFT;
            case "right" -> InteractEntityTaskRecord.Button.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + button);
        };
    }
}

