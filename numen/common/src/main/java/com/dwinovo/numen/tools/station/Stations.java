package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.sdk.CellOrEntity;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.task.station.StationTaskRecord;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.task.station.StationTaskRecord.Moved;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.List;
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

    /** 对一格方块。 */
    static <V> Pending<V> at(ServerCall call, BlockPos block, Class<? extends AbstractContainerMenu> menu, String kind,
                             List<Move> steps, Supplier<V> value) {
        return call.sync(new StationTaskRecord<V>(call, block, null, menu, kind, steps, value));
    }

    /** 对一格方块或一只实体;实体此刻得在附近,不在当场失败。 */
    static <V> Pending<V> at(ServerCall call, CellOrEntity target, Class<? extends AbstractContainerMenu> menu,
                             String kind, List<Move> steps, Supplier<V> value) {
        EntityRef entity = target.entity() == null ? null : EntityRef.of(call.entity(target.entity()));
        return call.sync(new StationTaskRecord<V>(call, target.cell(), entity, menu, kind, steps, value));
    }

    /** 一步搬出来的东西写成交回的值,先搬的在前。 */
    static List<Taken> taken(Moved moved) {
        List<Taken> out = new ArrayList<>();
        moved.kinds().forEach((item, n) -> out.add(new Taken(BuiltInRegistries.ITEM.getKey(item).toString(), n)));
        return out;
    }
}
