package com.dwinovo.numen.pathing.api;

import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.Breath;
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
 * 一次搜索没交出路时,结局是什么:搜索只知道自己为什么停,"没路是因为憋不住气、许的改动不够、没有料、改动预算不够、许可拒绝"
 * 要在同一份快照、同一个起点与目标上换一样条件再搜才看得出来。依次问,第一个问出路的就是结局:
 * <ol>
 *   <li>搜索因为憋不住气丢下过步子:憋得住就有路 → {@link Outcome.Breathless},连同那条路上头一段憋不住的水下——同样的规格下
 *       路是有的,缺的是气,这是最贴近的原因,先报;</li>
 *   <li>规格不许挖或不许放:两样都许就有路 → {@link Outcome.NeedsChanges};要问主人的格当墙时,再把它们算能走才有路
 *       → 同样是 {@link Outcome.NeedsChanges}(那条路的改动里有要问的格);都连同那条路要做的改动;</li>
 *   <li>挖与放都许、要问的格当墙:把要问的格算能走就有路 → {@link Outcome.NeedsChanges};</li>
 *   <li>身上没料:有料就有路 → {@link Outcome.NoMaterials};</li>
 *   <li>设了改动预算:不限预算就有路 → {@link Outcome.OverAlterBudget},连同最便宜那条要改几格;</li>
 *   <li>许可拒绝的格放行就有路 → {@link Outcome.Denied},连同路上第一格被拒的格与许可给的理由;</li>
 *   <li>都没有 → {@link Outcome.NoRoute}。</li>
 * </ol>
 * "许的改动不够"总是先报,身上有没有料不提:不许挖或不许放时问的是"都许又有料";都许而要问的格当墙时问的是"要问的格也算能走,
 * 料照身上的"——设想出来的料会让一条只缺料的路冒充成要主人同意的路。换条件的搜索与原来的同一个预算;它也搜不完,
 * 那一条就不算数。在工作线程上跑,只读快照。
 */
final class Diagnosis {

    /** 问"有料的话有没有路"时设想的料:一块普通的整块方块。 */
    private static final net.minecraft.world.level.block.Block ANY_BLOCK = Blocks.COBBLESTONE;

    private Diagnosis() {}

    /**
     * @param breathless 失败的那次搜索因为憋不住气丢下过步子({@link SearchResult#breathless})
     */
    static Outcome of(SearchResult.Stop stop, boolean breathless, Search failed, BooleanSupplier cancelled) {
        return switch (stop) {
            case BUDGET -> new Outcome.OutOfBudget();
            case UNLOADED -> new Outcome.Unloaded();
            case STRANDED -> new Outcome.Stranded(failed.start(), failed.view().getBlockState(failed.start()));
            case EXHAUSTED, CANCELLED, ARRIVED -> {
                Outcome breath = breathless ? breathless(failed, cancelled) : null;
                yield breath != null ? breath : exhausted(failed, cancelled);
            }
        };
    }

    /**
     * 憋得住的话有没有路:同样的规格,憋气从 {@link Breath#UNLIMITED} 起再搜;有路就按身体真实的憋气从起点重算一遍
     * ({@link Route#breathed}),交出头一段憋不住的水下。没有这样的路为 null。
     */
    private static Outcome breathless(Search failed, BooleanSupplier cancelled) {
        Route route = find(failed.breathing(Breath.UNLIMITED), failed.model(), cancelled);
        if (route == null) {
            return null;
        }
        Breath breath = failed.model().body().breath();
        for (Route.Dive dive : route.breathed(failed.model(), failed.air()).dives()) {
            if (!breath.lasts(dive.end())) {
                // 下水时能安全憋的 = 憋完还能安全憋的加上这一段憋掉的
                double spare = breath.spare(dive.end()) + dive.held();
                return new Outcome.Breathless(dive.from(), dive.to(), (int) Math.ceil(dive.held()),
                        (int) Math.max(0, Math.floor(spare)));
            }
        }
        return null;
    }

    private static Outcome exhausted(Search failed, BooleanSupplier cancelled) {
        CostModel model = failed.model();
        RouteSpec spec = model.spec();
        boolean hadMaterials = model.placing().isPresent();
        if (!spec.dig() || !spec.place()) {
            RouteSpec changing = spec.edit().changes(true).build();
            Route route = find(failed, withMaterials(model).withSpec(changing), cancelled);
            if (route == null && !spec.consent()) {
                route = find(failed, withMaterials(model).withSpec(changing.edit().consent(true).build()), cancelled);
            }
            return route != null ? new Outcome.NeedsChanges(route.edits().stream().filter(Edit::alters).toList())
                    : new Outcome.NoRoute();
        }
        if (!spec.consent()) {
            Route route = find(failed, model.withSpec(spec.edit().consent(true).build()), cancelled);
            if (route != null) {
                return new Outcome.NeedsChanges(route.edits().stream().filter(Edit::alters).toList());
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

    /** 同一份快照、同一个起点(连同接在哪一步后面、那时憋气的样子)、目标与预算,按这份成本模型搜到底;到了就交出路。 */
    private static Route find(Search failed, CostModel model, BooleanSupplier cancelled) {
        SearchResult result = AStar.run(new Search(failed.view(), model, failed.start(), failed.goal(),
                failed.budget(), Favoring.NONE, failed.budget(), failed.arrival(), failed.air()), cancelled);
        return result.arrived() ? result.route() : null;
    }
}
