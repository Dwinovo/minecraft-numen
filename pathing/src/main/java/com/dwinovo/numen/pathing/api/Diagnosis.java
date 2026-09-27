package com.dwinovo.numen.pathing.api;

import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.world.level.block.Blocks;

/**
 * 一次搜索没交出路时,结局是什么:搜索只知道自己为什么停,"没路是因为不许改地形、没有料、改动预算不够、许可拒绝"
 * 要在同一份快照、同一个起点与目标上换一样条件再搜才看得出来。依次问,第一个问出路的就是结局:
 * <ol>
 *   <li>规格不许改地形:许改(自然地形)就有路 → {@link Outcome.NeedsAlter},连同那条路要改几格;</li>
 *   <li>身上没料:有料就有路 → {@link Outcome.NoMaterials};</li>
 *   <li>设了改动预算:不限预算就有路 → {@link Outcome.OverAlterBudget},连同最便宜那条要改几格;</li>
 *   <li>许可拒绝的格放行就有路 → {@link Outcome.Denied},连同路上第一格被拒的格与许可给的理由;</li>
 *   <li>都没有 → {@link Outcome.NoRoute}。</li>
 * </ol>
 * 前一条换过的条件后面接着用(不许改地形又没料时,问的是"许改又有料"),所以"不许改地形"总是先报,身上有没有料不提。
 * 换条件的搜索与原来的同一个预算;它也搜不完,那一条就不算数。在工作线程上跑,只读快照。
 */
final class Diagnosis {

    /** 问"有料的话有没有路"时设想的料:一块普通的整块方块。 */
    private static final net.minecraft.world.level.block.Block ANY_BLOCK = Blocks.COBBLESTONE;

    private Diagnosis() {}

    static Outcome of(SearchResult.Stop stop, Search failed, BooleanSupplier cancelled) {
        return switch (stop) {
            case BUDGET -> new Outcome.OutOfBudget();
            case UNLOADED -> new Outcome.Unloaded();
            case STRANDED -> new Outcome.Stranded(failed.start(), failed.view().getBlockState(failed.start()));
            case EXHAUSTED, CANCELLED, ARRIVED -> exhausted(failed, cancelled);
        };
    }

    private static Outcome exhausted(Search failed, BooleanSupplier cancelled) {
        CostModel model = failed.model();
        RouteSpec spec = model.spec();
        boolean hadMaterials = model.placing().isPresent();
        if (!spec.alter().mayAlter()) {
            CostModel altering = withMaterials(model).withSpec(spec.edit().alter(RouteSpec.Alter.NATURAL).build());
            Route route = find(failed, altering, cancelled);
            return route != null ? new Outcome.NeedsAlter(route.alterations()) : new Outcome.NoRoute();
        }
        if (!hadMaterials && find(failed, withMaterials(model), cancelled) != null) {
            return new Outcome.NoMaterials();
        }
        if (spec.budgeted()) {
            Route route = find(failed, model.withSpec(spec.edit().alterBudget(RouteSpec.UNLIMITED).build()), cancelled);
            if (route != null) {
                return new Outcome.OverAlterBudget(route.alterations());
            }
        }
        TerrainPolicy original = model.terrain();
        Route route = find(failed, model.withTerrain((change, pos, state) -> {
            Permit permit = original.judge(change, pos, state);
            return permit instanceof Permit.Deny ? Permit.ALLOW : permit;
        }), cancelled);
        if (route != null) {
            for (Edit edit : route.edits()) {
                if (!edit.alters()) {
                    continue;
                }
                TerrainPolicy.Change change = edit instanceof Edit.Dig ? TerrainPolicy.Change.DIG
                        : TerrainPolicy.Change.PLACE;
                var state = edit instanceof Edit.Dig dig ? dig.state() : ((Edit.Place) edit).replaced();
                if (original.judge(change, edit.pos(), state) instanceof Permit.Deny deny) {
                    return new Outcome.Denied(edit.pos(), deny.reason());
                }
            }
        }
        return new Outcome.NoRoute();
    }

    private static CostModel withMaterials(CostModel model) {
        return model.placing().isPresent() ? model : model.withPlacing(ANY_BLOCK);
    }

    /** 同一份快照、同一个起点、目标与预算,按这份成本模型搜;到了就交出路。 */
    private static Route find(Search failed, CostModel model, BooleanSupplier cancelled) {
        SearchResult result = AStar.run(new Search(failed.view(), model, failed.start(), failed.goal(),
                failed.budget(), Favoring.NONE), cancelled);
        return result.arrived() ? result.route() : null;
    }
}
