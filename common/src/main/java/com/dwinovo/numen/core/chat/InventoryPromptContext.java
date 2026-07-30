package com.dwinovo.numen.core.chat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Supplies a compact, revision-cached inventory fact block to an entity model turn. */
public final class InventoryPromptContext {
    private static final int UNSEEN = Integer.MIN_VALUE;

    private Object bodyIdentity;
    private int revision = UNSEEN;
    private int selectedSlot = UNSEEN;
    private String rendered = "";

    public String refresh(int revision, int selectedSlot, Supplier<Snapshot> capture) {
        return refresh(this, revision, selectedSlot, capture);
    }

    public String refresh(Object bodyIdentity, int revision, int selectedSlot, Supplier<Snapshot> capture) {
        if (this.bodyIdentity == bodyIdentity
            && this.revision == revision
            && this.selectedSlot == selectedSlot) {
            return this.rendered;
        }
        this.rendered = render(capture.get());
        this.bodyIdentity = bodyIdentity;
        this.revision = revision;
        this.selectedSlot = selectedSlot;
        return this.rendered;
    }

    private static String render(Snapshot snapshot) {
        Map<String, Integer> totals = new TreeMap<>();
        for (Stack stack : snapshot.slots()) {
            if (!stack.empty()) {
                totals.merge(stack.itemId(), stack.count(), Integer::sum);
            }
        }
        String items = totals.isEmpty()
            ? "empty"
            : totals.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return "\n\n<current_inventory source=\"live_model_turn\">\n"
            + "This is a verified snapshot of your body for the current model turn. "
            + "Use it for item presence and total counts; do not call get_self_status merely to rediscover these items. "
            + "Use inspect_gui only when exact slots are required. A newer tool result overrides this snapshot.\n"
            + "main_hand=" + snapshot.mainHand().display() + "\n"
            + "off_hand=" + snapshot.offHand().display() + "\n"
            + "items=" + items + "\n"
            + "</current_inventory>";
    }

    public record Stack(String itemId, int count) {
        public Stack {
            itemId = itemId == null || itemId.isBlank() ? "minecraft:air" : itemId;
            count = Math.max(0, count);
        }

        private boolean empty() {
            return count == 0 || "minecraft:air".equals(itemId);
        }

        private String display() {
            return empty() ? "minecraft:air" : itemId + " x" + count;
        }
    }

    public record Snapshot(Stack mainHand, Stack offHand, List<Stack> slots) {
        public Snapshot {
            mainHand = mainHand == null ? new Stack("minecraft:air", 0) : mainHand;
            offHand = offHand == null ? new Stack("minecraft:air", 0) : offHand;
            slots = slots == null ? List.of() : List.copyOf(slots);
        }
    }
}
