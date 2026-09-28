package com.dwinovo.numen.pathing.api;

import java.util.List;
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
 * 一次搜索没交出路时,结局是什么:搜索只知道自己为什么停,"没路是因为许改的不够、没有料、改动预算不够、许可拒绝"
 * 要在同一份快照、同一个起点与目标上换一样条件再搜才看得出来。依次问,第一个问出路的就是结局:
 * <ol>
 *   <li>规格不许改地形:许改自然地形就有路 → {@link Outcome.NeedsAlter}({@code NATURAL});连要主人同意的格也许改才有路
 *       → {@link Outcome.NeedsAlter}({@code ANY});都连同那条路要改几格;</li>
 *   <li>只许改自然地形:连要主人同意的格也许改就有路 → {@link Outcome.NeedsAlter}({@code ANY});</li>
 *   <li>身上没料:有料就有路 → {@link Outcome.NoMaterials};</li>
 *   <li>设了改动预算:不限预算就有路 → {@link Outcome.OverAlterBudget},连同最便宜那条要改几格;</li>
 *   <li>许可拒绝的格放行就有路 → {@link Outcome.Denied},连同路上第一格被拒的格与许可给的理由;</li>
 *   <li>都没有 → {@link Outcome.NoRoute}。</li>
 * </ol>
 * "许改的不够"总是先报,身上有没有料不提:不许改地形时问的是"许改又有料";只许改自然地形时问的是"连要同意的格也许改,
 * 料照身上的"——设想出来的料会让一条只缺料的路冒充成要主人同意的路。换条件的搜索与原来的同一个预算;它也搜不完,
 * 那一条就不算数。在工作线程上跑,只读快照。
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
            for (RouteSpec.Alter level : List.of(RouteSpec.Alter.NATURAL, RouteSpec.Alter.ANY)) {
                Route route = find(failed, withMaterials(model).withSpec(spec.edit().alter(level).build()), cancelled);
                if (route != null) {
                    return new Outcome.NeedsAlter(level, route.alterations());
                }
            }
            return new Outcome.NoRoute();
        }
        if (spec.alter() == RouteSpec.Alter.NATURAL) {
            Route route = find(failed, model.withSpec(spec.edit().alter(RouteSpec.Alter.ANY).build()), cancelled);
            if (route != null) {
                return new Outcome.NeedsAlter(RouteSpec.Alter.ANY, route.alterations());
            }
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
        Route route = find(failed, model.withTerrain((change, pos, state, view) -> {
            Permit permit = original.judge(change, pos, state, view);
            return permit instanceof Permit.Deny ? Permit.ALLOW : permit;
        }), cancelled);
        if (route != null) {
            for (Edit edit : route.edits()) {
                Permit permit = switch (edit) {
                    case Edit.Dig dig -> original.judge(TerrainPolicy.Change.DIG, dig.pos(), dig.state(), failed.view());
                    case Edit.Place place -> original.judge(new TerrainPolicy.Change.Place(place.block()), place.pos(),
                            place.replaced(), failed.view());
                    case Edit.Catch caught -> original.judge(new TerrainPolicy.Change.Place(Blocks.WATER), caught.pos(),
                            caught.replaced(), failed.view());
                    case Edit.Door door -> Permit.ALLOW;
                };
                if (permit instanceof Permit.Deny deny) {
                    return new Outcome.Denied(edit.pos(), deny.reason());
                }
            }
        }
        return new Outcome.NoRoute();
    }

    private static CostModel withMaterials(CostModel model) {
        return model.placing().isPresent() ? model : model.withPlacing(ANY_BLOCK);
    }

    /** 同一份快照、同一个起点(连同接在哪一步后面)、目标与预算,按这份成本模型搜到底;到了就交出路。 */
    private static Route find(Search failed, CostModel model, BooleanSupplier cancelled) {
        SearchResult result = AStar.run(new Search(failed.view(), model, failed.start(), failed.goal(),
                failed.budget(), Favoring.NONE, failed.budget(), failed.arrival()), cancelled);
        return result.arrived() ? result.route() : null;
    }
}
