package com.dwinovo.numen.api.platform.services;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个方块里装着什么的结构化读数:加载器只负责读出这些数,给模型看的文字怎么写只在 {@link #lines()} 这一处。
 *
 * <p>物品、液体各有一个或几个"存储",每个存储记着是从哪几面露出来的(见 {@link Exposures});能量至多一份。
 * 液体量一律是毫桶,能量一律是 FE,单位换算属于加载器实现。
 */
public record BlockStorageReading(List<Exposed<ItemInventory>> items,
                                  List<Exposed<FluidTanks>> fluids,
                                  Energy energy) {

    /** 物品格里的一堆;空格的 id 是空串、数量 0。 */
    public record ItemSlot(String id, long count) {
        public static final ItemSlot EMPTY = new ItemSlot("", 0);

        boolean isEmpty() {
            return count <= 0 || id.isEmpty();
        }
    }

    /** 一个物品存储:按格序排的每一格。 */
    public record ItemInventory(List<ItemSlot> slots) {}

    /** 一个液体槽;没有液体时 id 为 null、amountMb 为 0。 */
    public record FluidTank(String id, long amountMb, long capacityMb) {}

    /** 一个液体存储:按槽序排的每一槽。 */
    public record FluidTanks(List<FluidTank> tanks) {}

    /** 能量的存量、上限,以及能不能被灌进、被抽出。 */
    public record Energy(long stored, long max, boolean accepts, boolean provides) {}

    /** 一个存储,和露出它的那几面("all" 是不指定面)。 */
    public record Exposed<V>(List<String> sides, V storage) {}

    /** 给模型看的文字行:每个存储一个标题,每个非空的格、每个液体槽一行,最后是能量;什么都没有就是空表。 */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Exposed<ItemInventory> e = items.get(i);
            List<ItemSlot> slots = e.storage().slots();
            out.add("items" + (items.size() > 1 ? " #" + i : "") + " (sides: " + String.join(",", e.sides())
                    + "), " + slots.size() + " slots:");
            boolean any = false;
            for (int s = 0; s < slots.size(); s++) {
                ItemSlot slot = slots.get(s);
                if (slot.isEmpty()) continue;
                any = true;
                out.add("  slot " + s + ": " + slot.id() + " x" + slot.count());
            }
            if (!any) {
                out.add("  (all " + slots.size() + " slots empty)");
            }
        }
        for (int i = 0; i < fluids.size(); i++) {
            Exposed<FluidTanks> e = fluids.get(i);
            out.add("fluids" + (fluids.size() > 1 ? " #" + i : "") + " (sides: " + String.join(",", e.sides()) + "):");
            List<FluidTank> tanks = e.storage().tanks();
            for (int t = 0; t < tanks.size(); t++) {
                FluidTank tank = tanks.get(t);
                out.add("  tank " + t + ": " + (tank.id() == null ? "empty" : tank.id() + " " + tank.amountMb())
                        + "/" + tank.capacityMb() + " mB");
            }
        }
        if (energy != null) {
            List<String> io = new ArrayList<>();
            if (energy.accepts()) io.add("accepts");
            if (energy.provides()) io.add("provides");
            out.add("energy: " + energy.stored() + "/" + energy.max() + " FE"
                    + (io.isEmpty() ? "" : " (" + String.join("/", io) + ")"));
        }
        return out;
    }
}
