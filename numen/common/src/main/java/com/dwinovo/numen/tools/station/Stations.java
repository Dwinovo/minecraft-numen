package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.CellOrEntity;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.task.station.StationTaskRecord.Step;
import com.dwinovo.numen.task.station.StationTaskRecord.Moved;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * "对一个目标做一件事"的函数共用的那一步:把目标、要它打开的界面种类、界面里的几步交给一件活({@link StationTaskRecord}),这次调用等它
 * 做完。各组函数({@code numen.container}、{@code numen.furnace}……)只说自己的几步,开界面、合界面都在那件活里。
 */
public final class Stations {

    private Stations() {}

    /** 拿出来的一种东西。 */
    @Doc("One kind of item taken out.")
    public record Taken(@Doc("The item id, minecraft:iron_ingot.") String item,
                        @Doc("How many came out.") int count) {}

    /** 做出来的一样东西:附魔好的、铁砧打出来的、织好的、买到的。 */
    @Doc("What a station made, as it is now.")
    public record Product(@Doc("The item id, minecraft:diamond_sword.") String item,
                          @Doc("How many.") int count,
                          @Doc("What tells it apart from the same item without it: enchantments, a custom name, a "
                                  + "banner's patterns, damage. Absent for a plain item.") Optional<String> detail) {}

    /** 对一格方块。别的组里也对着一格做事的函数({@code numen.inv.craft} 在工作台上合)同用这一个。 */
    public static <V> Pending<V> at(ServerCall call, BlockPos block, Class<? extends AbstractContainerMenu> menu,
                                    String kind, List<? extends Step> steps, Supplier<V> value) {
        return call.sync(new StationTaskRecord<V>(call, block, null, menu, kind, steps, value));
    }

    /** 对一只实体;它此刻得在附近,不在当场失败。 */
    static <V> Pending<V> at(ServerCall call, EntityRef entity, Class<? extends AbstractContainerMenu> menu, String kind,
                             List<? extends Step> steps, Supplier<V> value) {
        return call.sync(new StationTaskRecord<V>(call, null, EntityRef.of(call.entity(entity)), menu, kind, steps, value));
    }

    /** 对一格方块或一只实体。 */
    static <V> Pending<V> at(ServerCall call, CellOrEntity target, Class<? extends AbstractContainerMenu> menu,
                             String kind, List<? extends Step> steps, Supplier<V> value) {
        return target.entity() == null ? at(call, target.cell(), menu, kind, steps, value)
                : at(call, target.entity(), menu, kind, steps, value);
    }

    /** 一步搬出来的东西写成交回的值,先搬的在前。 */
    static List<Taken> taken(Moved moved) {
        List<Taken> out = new ArrayList<>();
        moved.kinds().forEach((item, n) -> out.add(new Taken(id(item), n)));
        return out;
    }

    /** 一种物品的 id,{@code minecraft:stone}:说给模型的话里点名一种物品用它。 */
    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 这一叠写成交回的值。 */
    static Product product(ItemStack stack) {
        return product(stack, stack.getCount());
    }

    /** 同上,件数由调用方给(几次做出来的合在一起)。 */
    static Product product(ItemStack stack, int count) {
        String detail = StackLabel.detail(stack);
        return new Product(id(stack.getItem()), count, detail.isEmpty() ? Optional.empty() : Optional.of(detail));
    }

    /**
     * 界面里的一步:结果那一格出了东西就记下它是什么,等下一步把它拿出来;没出就带着 {@code nothing} 那句话收场。放进去的几样做不做得出
     * 东西由原版在放进去的那一刻算好,这里只读。
     *
     * @param slot    结果那一格
     * @param into    出了的东西记在这里
     * @param nothing 没出东西时说的话:读到的事实
     */
    static Deed result(int slot, AtomicReference<Product> into, Supplier<String> nothing) {
        return new Deed((menu, her) -> {
            ItemStack made = menu.getSlot(slot).getItem();
            if (made.isEmpty()) {
                throw new ApiError(ErrorKind.FAILED, nothing.get(), null);
            }
            into.set(product(made));
            return null;
        });
    }

    /**
     * 件数、次数这类可以不写的数:写了就得至少是 1,不是就当场按参数错误退回,不走到目标跟前才发现。
     *
     * @return 写了的那个数;没写是 null
     */
    static Integer count(String name, Optional<Integer> given) {
        if (given.isPresent() && given.get() < 1) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, name + " is at least 1; got " + given.get(), null);
        }
        return given.orElse(null);
    }
}
