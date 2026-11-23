package com.dwinovo.numen.api.platform.services;

import java.util.List;

/**
 * 一个方块里装着什么的结构化读数:加载器只负责读出这些数,给模型看成什么样由用它的一方定。
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

        public boolean isEmpty() {
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
}
