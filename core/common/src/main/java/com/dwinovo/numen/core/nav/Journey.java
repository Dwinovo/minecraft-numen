package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.drive.EditLedger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一件活一路上真改了世界的哪几格、身体为走路做了什么:这件活开过的每一趟路交出的实际账({@link Report}),加上活自己为了够着
 * 目标挖掉的遮挡物。"她挖了什么、放了什么"只认这一本,回执末尾照它说,要分清"她挖的"与"别人动的"也问它。
 */
public final class Journey {

    private final List<EditLedger.Entry> entries = new ArrayList<>();
    private final List<BodyAction> actions = new ArrayList<>();

    /** 并进一趟路的实际账。 */
    public void add(Report report) {
        entries.addAll(report.ledger().entries());
        actions.addAll(report.actions());
    }

    /** 活自己为了干活让身体做了 {@code action}(比如把挖它最快的那件工具拿到手上)。 */
    public void did(BodyAction action) {
        actions.add(action);
    }

    /** 活自己挖掉了 {@code pos}(原来是 {@code before}),比如为了拉出射线挖掉的遮挡物。 */
    public void dug(BlockPos pos, BlockState before) {
        entries.add(new EditLedger.Dug(pos.immutable(), before, null));
    }

    /** 这本账加上还在走的几趟路的账。 */
    public Journey plus(List<Report> live) {
        Journey out = new Journey();
        out.entries.addAll(entries);
        out.actions.addAll(actions);
        for (Report report : live) {
            out.add(report);
        }
        return out;
    }

    /** 账上挖过这一格。 */
    public boolean broke(BlockPos pos) {
        for (EditLedger.Entry entry : entries) {
            if (entry instanceof EditLedger.Dug && entry.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 放下、之后没再挖掉的方块,按放下的先后。 */
    public List<EditLedger.Placed> placedBlocks() {
        return EditLedger.placedBlocks(entries);
    }

    public List<EditLedger.Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<BodyAction> actions() {
        return Collections.unmodifiableList(actions);
    }

    /** 回执末尾那一段;什么都没改、身体也没为走路做什么时是空串。 */
    public String describe() {
        return NavText.journey(entries, actions);
    }
}
