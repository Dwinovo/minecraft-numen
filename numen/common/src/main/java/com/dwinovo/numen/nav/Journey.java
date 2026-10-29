package com.dwinovo.numen.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.drive.DiveLog;
import com.dwinovo.numen.pathing.drive.EditLedger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 一件活一路上真改了世界的哪几格、身体为走路做了什么、潜过几段水、权限层与主人对哪几格怎么裁:这件活开过的每一趟路交出的实际账
 * ({@link Report})与裁定({@link Ruling}),加上活自己为了够着目标挖掉的遮挡物。"她挖了什么、放了什么、被拒了什么"只认这一本,
 * 回执末尾照它说({@link #describe}),路程回执的数字也从它出({@link #walked}),要分清"她挖的"与"别人动的"也问它。
 */
public final class Journey {

    private final List<EditLedger.Entry> entries = new ArrayList<>();
    private final List<BodyAction> actions = new ArrayList<>();
    private final List<DiveLog.Dive> dives = new ArrayList<>();
    private final List<Ruling> rulings = new ArrayList<>();

    /** 并进一趟路的实际账。 */
    public void add(Report report) {
        entries.addAll(report.ledger().entries());
        actions.addAll(report.actions());
        dives.addAll(report.dives());
    }

    /** 并进另一本账:一件活派下的子活收场时,它的账归这件活。 */
    public void add(Journey other) {
        entries.addAll(other.entries);
        actions.addAll(other.actions);
        dives.addAll(other.dives);
        rulings.addAll(other.rulings);
    }

    /** 并进路上权限层与主人的裁定。 */
    public void ruled(List<Ruling> more) {
        rulings.addAll(more);
    }

    /** 活自己为了干活让身体做了 {@code action}(比如把挖它最快的那件工具拿到手上)。 */
    public void did(BodyAction action) {
        actions.add(action);
    }

    /** 活自己挖掉了 {@code pos}(原来是 {@code before}),比如为了拉出射线挖掉的遮挡物。 */
    public void dug(BlockPos pos, BlockState before) {
        entries.add(new EditLedger.Dug(pos.immutable(), before));
    }

    /** 这本账加上还在走的几趟路的账与裁定。 */
    public Journey plus(List<Report> live, List<Ruling> liveRulings) {
        Journey out = new Journey();
        out.add(this);
        for (Report report : live) {
            out.add(report);
        }
        out.rulings.addAll(liveRulings);
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

    public List<EditLedger.Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<BodyAction> actions() {
        return Collections.unmodifiableList(actions);
    }

    /** 回执末尾那一段;什么都没改、身体没为走路做什么、没潜过水、也没被拒过时是空串。 */
    public String describe() {
        return NavText.journey(entries, actions, dives, rulings);
    }

    /** 路程回执:停在 {@code pos}、离要去的地方还差 {@code distanceLeft} 格,加上这本账里挖了几格、放了几格、裁定了什么。 */
    public Walked walked(Vec3 pos, double distanceLeft) {
        NavText.Tally edits = NavText.tally(entries);
        return new Walked(pos, distanceLeft, edits.dugCount(), edits.placedCount(), edits.dugCells(), edits.placedCells(),
                rulings);
    }
}
