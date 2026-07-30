package com.dwinovo.numen.core.chat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class InventoryPromptContextTest {
    @Test
    void refreshesOnlyWhenVerifiedInventoryFactsChange() {
        InventoryPromptContext context = new InventoryPromptContext();
        AtomicInteger captures = new AtomicInteger();

        String first = context.refresh(7, 2, () -> {
            captures.incrementAndGet();
            return snapshot("minecraft:bow", "minecraft:shield", List.of(
                stack("minecraft:bow", 1),
                stack("minecraft:arrow", 3),
                stack("minecraft:bow", 1),
                stack("minecraft:air", 0)
            ));
        });
        require(first.contains("main_hand=minecraft:bow x1"), "the current hand must be visible to the model");
        require(first.contains("off_hand=minecraft:shield x1"), "the off hand must be visible to the model");
        require(first.contains("minecraft:bow x2"), "matching stacks must be aggregated into one compact count");
        require(first.contains("minecraft:arrow x3"), "newly received ammunition must be visible");
        require(!first.contains("minecraft:air"), "empty slots must not waste prompt space");

        String unchanged = context.refresh(7, 2, () -> {
            captures.incrementAndGet();
            return snapshot("minecraft:crossbow", "minecraft:air", List.of(stack("minecraft:crossbow", 1)));
        });
        require(first.equals(unchanged), "an unchanged inventory revision must reuse the same prompt snapshot");
        require(captures.get() == 1, "an unchanged model turn must not rescan every inventory slot");

        String received = context.refresh(8, 2, () -> {
            captures.incrementAndGet();
            return snapshot("minecraft:bow", "minecraft:shield", List.of(
                stack("minecraft:bow", 1),
                stack("minecraft:arrow", 5)
            ));
        });
        require(received.contains("minecraft:arrow x5"), "an inventory change must invalidate the cached facts");
        require(captures.get() == 2, "one changed revision must cause exactly one fresh capture");

        context.refresh(8, 4, () -> {
            captures.incrementAndGet();
            return snapshot("minecraft:arrow", "minecraft:shield", List.of(
                stack("minecraft:bow", 1),
                stack("minecraft:arrow", 5)
            ));
        });
        require(captures.get() == 3, "changing the selected hand must refresh even when item counts stay unchanged");

        InventoryPromptContext respawnedContext = new InventoryPromptContext();
        AtomicInteger respawnCaptures = new AtomicInteger();
        Object oldBody = new Object();
        Object newBody = new Object();
        respawnedContext.refresh(oldBody, 1, 0, () -> {
            respawnCaptures.incrementAndGet();
            return snapshot("minecraft:bow", "minecraft:air", List.of(stack("minecraft:bow", 1)));
        });
        String afterRespawn = respawnedContext.refresh(newBody, 1, 0, () -> {
            respawnCaptures.incrementAndGet();
            return snapshot("minecraft:crossbow", "minecraft:air", List.of(stack("minecraft:crossbow", 1)));
        });
        require(
            afterRespawn.contains("minecraft:crossbow"),
            "a replacement companion body must not inherit an equal revision from the dead body"
        );
        require(respawnCaptures.get() == 2, "a new body identity must invalidate the previous body's snapshot");
    }

    private static InventoryPromptContext.Snapshot snapshot(
        String mainHand,
        String offHand,
        List<InventoryPromptContext.Stack> slots
    ) {
        return new InventoryPromptContext.Snapshot(stack(mainHand, 1), stack(offHand, 1), slots);
    }

    private static InventoryPromptContext.Stack stack(String itemId, int count) {
        return new InventoryPromptContext.Stack(itemId, count);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
