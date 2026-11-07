package com.dwinovo.numen.task.inventory;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * 在她打开的界面里按种类搬东西({@code gui put} 放进去、{@code gui take} 拿出来):这一种全搬,或搬够 {@code count} 件。
 *
 * @see GuiItemsCompanionTask
 */
public final class GuiItemsTaskRecord extends TaskRecord<Integer> {

    public final Item item;
    /** 搬几件;null 是这一种全搬。 */
    public final Integer count;
    /** 从她的背包放进界面那一侧;false 是从界面那一侧拿进她的背包。 */
    public final boolean put;

    public GuiItemsTaskRecord(ServerCall source, long deadlineGameTime, Item item, Integer count, boolean put) {
        super(source, deadlineGameTime);
        this.item = item;
        this.count = count;
        this.put = put;
    }

    @Override
    public String describe() {
        return getToolName() + " " + BuiltInRegistries.ITEM.getKey(item).getPath() + (count == null ? "" : " " + count);
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        String name = BuiltInRegistries.ITEM.getKey(item).getPath();
        String key = "numen.activity." + (put ? "gui_put" : "gui_take") + (count == null ? "_all" : "");
        return count == null ? Activity.of(key, name) : Activity.of(key, name, count);
    }
}
