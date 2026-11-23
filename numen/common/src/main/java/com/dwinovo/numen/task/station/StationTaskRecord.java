package com.dwinovo.numen.task.station;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对一个目标做一件事:走过去右键它,在它打开的界面里按 {@link #steps} 一步步做,做完合上。目标是一格方块或一只实体;界面只在这件活
 * 里面,派这件活的函数与读回执的她都不碰它。
 *
 * @param <V> 这件活交回的值
 * @see StationCompanionTask
 */
public final class StationTaskRecord<V> extends TaskRecord<V> {

    /** 这件活自己的期限。走过去、点开、搬东西都是子活,各按各的期限走,它们跑的时候这一个不走表。 */
    private static final long TIMEOUT_TICKS = 30 * 20;

    public final ServerCall source;
    /** 目标是一格方块;是实体时为 null。 */
    public final BlockPos block;
    /** 目标是一只实体;是方块时为 null。 */
    public final EntityRef entity;
    /** 它打开的得是这一种界面;不挑是 null。 */
    public final Class<? extends AbstractContainerMenu> menu;
    /** 函数管这种目标叫什么({@code furnace}、{@code container}),回执里用。 */
    public final String kind;
    public final List<Step> steps;
    /** 做完之后交回的值,从各步留下的东西里读。 */
    public final Supplier<V> value;

    public StationTaskRecord(ServerCall source, BlockPos block, EntityRef entity,
                             Class<? extends AbstractContainerMenu> menu, String kind, List<? extends Step> steps,
                             Supplier<V> value) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        if ((block == null) == (entity == null)) {
            throw new IllegalArgumentException("a block or an entity, exactly one of them");
        }
        this.source = source;
        this.block = block == null ? null : block.immutable();
        this.entity = entity;
        this.menu = menu;
        this.kind = kind;
        this.steps = List.copyOf(steps);
        this.value = value;
    }

    @Override
    public String describe() {
        return getToolName() + " " + (block != null ? "@" + block.getX() + "," + block.getY() + "," + block.getZ()
                : entity.label());
    }

    /** 界面里的一步:搬东西({@link Move}),或当场做完的一件事({@link Deed})。 */
    public sealed interface Step permits Move, Deed {}

    /**
     * 搬东西:放进去从她的背包搬到界面那一侧,拿出来反过来。
     *
     * @param which    搬哪一种:放进去的是她带着的那一种(只说了物品,或点名的一种),拿出来的按物品认;拿的时候 null 是那几格里有什么拿什么
     * @param count    搬几件;null 是全搬
     * @param slots    界面那一侧只动这几格;null 是每一格,放进哪一格由界面自己分
     * @param role     这几格在这种目标里叫什么({@code fuel}、{@code the output}),回执里用;没有名堂是 null
     * @param optional 一件都没有也算做完(取产物时还没烧出来)
     * @param moved    搬完之后搬了什么,由这件活填
     */
    public record Move(boolean put, Stacks.Which which, Integer count, int[] slots, String role, boolean optional,
                       Moved moved) implements Step {

        public static Move put(Stacks.Which which, Integer count, int[] slots, String role) {
            return new Move(true, which, count, slots, role, false, new Moved());
        }

        public static Move take(Item item, Integer count, int[] slots, String role, boolean optional) {
            return new Move(false, item == null ? null : Stacks.Which.of(item), count, slots, role, optional, new Moved());
        }
    }

    /**
     * 界面里当场做完的一件事:选一项、改个名、读一眼。把原版客户端会发的那个包交给原版,再读回来界面真的变了没有。
     */
    public record Deed(Act act) implements Step {}

    /** {@link Deed} 做的事。 */
    @FunctionalInterface
    public interface Act {

        /**
         * @param menu 这件活打开的界面(已经认过是 {@link StationTaskRecord#menu} 那一种)
         * @return 做了什么,一句话,进回执(整件活只是读一眼的,说读了什么);夹在别的步骤中间、没什么可说的是 null
         * @throws com.dwinovo.numen.script.ApiError 原版没让做:说读到的事实(要多少、有多少)
         */
        String perform(AbstractContainerMenu menu, NumenPlayer her);
    }

    /** 一步搬完之后搬了什么:按种类记件数,先搬的在前。这一步还没做是空的。 */
    public static final class Moved {

        private Map<Item, Integer> kinds = Map.of();

        void set(Map<Item, Integer> kinds) {
            this.kinds = new LinkedHashMap<>(kinds);
        }

        public Map<Item, Integer> kinds() {
            return java.util.Collections.unmodifiableMap(kinds);
        }

        /** 一共搬了几件。 */
        public int count() {
            return kinds.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}
