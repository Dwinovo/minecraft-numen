package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.api.Bill;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 许不许改地形:不许改时绕行或报"要改地形";许改时挖穿、垫柱、搭桥、向下挖;许可拒绝的格不碰、要问的格列进账单;
 * 能绕就不挖;桥位上有单层雪时块放进那一格;不挖托着自己的那一格;改动预算不够;世界边界;按种类禁挖;不挖要去用的工作台。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class AlterGameTests {

    private static final String BATCH = "pathing_alter";
    private static final String BORDER = "pathing_border";

    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();
    private static final RouteSpec ANY = RouteSpec.defaults().edit().alter(RouteSpec.Alter.ANY).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 一道横贯场地的墙,{@code height} 格高,{@code gapZ} 处留口(为负不留)。 */
    private static void wall(Trial t, int x, int height, Block block, int gapZ) {
        for (int z = 0; z <= 39; z++) {
            if (z != gapZ) {
                t.fill(x, 1, z, x, height, z, block);
            }
        }
    }

    /**
     * 一间泥土小屋:四面墙与屋顶都是泥土,屋里三格见方、三格高;身体关在里面,出去只能挖墙(屋顶压着,垫柱也翻不出去)。
     */
    private static void hut(Trial t) {
        t.fill(2, 1, 2, 8, 4, 8, Blocks.DIRT);
        t.fill(3, 1, 3, 7, 3, 7, Blocks.AIR);
    }

    /** 高出地面四格、中间隔着 {@code x0..x1} 一道沟的两块基岩台子:沟挖不动,跳下去也摔不起,只能搭桥。 */
    private static void ditch(Trial t, int x0, int x1) {
        t.fill(0, 1, 0, x0 - 1, 4, 39, Blocks.BEDROCK);
        t.fill(x1 + 1, 1, 0, 39, 4, 39, Blocks.BEDROCK);
    }

    private static List<EditLedger.Dug> dug(Trial.Run r) {
        List<EditLedger.Dug> out = new ArrayList<>();
        for (EditLedger.Entry e : r.report.ledger().entries()) {
            if (e instanceof EditLedger.Dug d) {
                out.add(d);
            }
        }
        return out;
    }

    private static List<EditLedger.Placed> placed(Trial.Run r) {
        List<EditLedger.Placed> out = new ArrayList<>();
        for (EditLedger.Entry e : r.report.ledger().entries()) {
            if (e instanceof EditLedger.Placed p) {
                out.add(p);
            }
        }
        return out;
    }

    // ==================== 不许改地形 ====================

    /** 一道挖得动的泥土墙,远处留着口:不许改地形,走口里绕过去,一格不动。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void detours_a_wall_it_may_not_dig(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT, 20);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).within(600).arrives().then(Scenes::unaltered);
    }

    /** 泥土墙横贯场地,没有口:不许改地形时报"许改自然地形就有路、要改两格",世界一格不变。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void needs_alter_when_it_cannot_get_around(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT, -1);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), RouteSpec.defaults()).within(300)
                .fails(Outcome.NeedsAlter.class, o -> {
                    if (o.level() != RouteSpec.Alter.NATURAL || o.alterations() <= 0) {
                        throw new GameTestAssertException("应当是许改自然地形就有路:" + o);
                    }
                });
    }

    // ==================== 改地形 ====================

    /** 泥土墙横贯场地:许改自然地形,挖开身体高的两格穿过去,挖掉的正是墙上那两格。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void digs_through_a_dirt_wall(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT, -1);
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(400).arrives().then(r -> {
            List<EditLedger.Dug> dug = dug(r);
            if (dug.size() != 2 || dug.stream().anyMatch(d -> d.pos().getX() - t.origin.getX() != 8
                    || !d.before().is(Blocks.DIRT))) {
                throw new GameTestAssertException("应当挖开墙上身体高的两格:" + dug);
            }
        });
    }

    /** 四格高的石崖,身上有圆石:垫块上去(上四格要垫三块,最后一下跳上崖),不去空手刨石头。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void pillars_up_a_cliff(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 39, 4, 39, Blocks.STONE);
        TestBody body = t.body(5, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(14, 5, 5)), NATURAL).within(600).arrives().then(r -> {
            if (!dug(r).isEmpty() || placed(r).size() < 3) {
                throw new GameTestAssertException("应当垫柱上去、不挖:" + r.report.ledger().entries());
            }
        });
    }

    /** 两块基岩台子之间一道四格宽的沟:身上有圆石,在台面那一层搭四格桥过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void bridges_a_ditch(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ditch(t, 10, 13);
        TestBody body = t.body(6, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> {
            List<EditLedger.Placed> placed = placed(r);
            if (placed.size() != 4 || placed.stream().anyMatch(p -> p.pos().getY() - t.origin.getY() != 4)) {
                throw new GameTestAssertException("应当在台面那一层搭四格桥:" + placed);
            }
        });
    }

    /** 站在四格厚的泥土上,去处在正下方的地面:向下挖下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void digs_down_to_a_lower_goal(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 10, 4, 10, Blocks.DIRT);
        TestBody body = t.body(5, 5, 5);
        t.go(body, Goals.at(t.at(5, 1, 5)), NATURAL).within(600).arrives().then(r -> {
            if (dug(r).size() < 4) {
                throw new GameTestAssertException("应当挖下去四格:" + dug(r));
            }
        });
    }

    /**
     * 泥土墙横贯场地,正对着的那一段是玩家砌的(许可拒绝),墙里还嵌着一只箱子(许可拒绝):绕到许可放行的那一段去挖,
     * 拒绝的格一格不碰。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void never_digs_denied_cells(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT, -1);
        t.set(8, 1, 5, Blocks.CHEST);
        BlockPos wallStart = t.at(8, 1, 0);
        t.terrain = (change, pos, state, view) -> state.is(Blocks.CHEST) ? Permit.deny("chest")
                : pos.getX() == wallStart.getX() && pos.getZ() - wallStart.getZ() <= 12 ? Permit.deny("player placed")
                : Permit.ALLOW;
        TestBody body = t.body(4, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(600).arrives().then(r -> {
            for (EditLedger.Dug d : dug(r)) {
                if (d.pos().getZ() - wallStart.getZ() <= 12) {
                    throw new GameTestAssertException("挖了许可拒绝的格 " + t.rel(d.pos()));
                }
            }
        });
    }

    /** 关在泥土小屋里,整间屋子许可都拒绝:结局是"许可拒绝",点出屋墙上的一格与许可给的理由,世界一格不变。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reports_the_denied_cell(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        hut(t);
        t.terrain = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.deny("owner's hut") : Permit.ALLOW;
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(300).fails(Outcome.Denied.class, o -> {
            if (!"owner's hut".equals(o.reason()) || !t.state(o.cell().getX() - t.origin.getX(),
                    o.cell().getY() - t.origin.getY(), o.cell().getZ() - t.origin.getZ()).is(Blocks.DIRT)) {
                throw new GameTestAssertException("应当点出屋墙上的一格与许可的理由:" + o);
            }
        }).then(Scenes::unaltered);
    }

    /**
     * 关在泥土小屋里,屋子许可答"要问":{@code alter=any} 下挖出去,实际账单里列出挖的每一格与许可给的凭据;
     * 只许改自然地形时,结局是"放宽到 any 才有路"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void lists_cells_that_need_consent(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        hut(t);
        t.terrain = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.ask("hut:" + t.rel(pos)) : Permit.ALLOW;
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), ANY).within(400).arrives().then(r -> {
            Bill bill = r.report.bill();
            if (bill.consents().isEmpty() || bill.consents().size() != bill.digs().size()) {
                throw new GameTestAssertException("挖的每一格都要列进要同意的格:" + bill);
            }
            for (Bill.Consent c : bill.consents()) {
                if (!("hut:" + t.rel(c.pos())).equals(c.credential())) {
                    throw new GameTestAssertException("凭据没有原样交还:" + c);
                }
            }
        });
        TestBody other = t.body(5, 1, 20);
        t.fill(2, 1, 17, 8, 4, 23, Blocks.DIRT);
        t.fill(3, 1, 18, 7, 3, 22, Blocks.AIR);
        t.go(other, Goals.at(t.at(12, 1, 20)), NATURAL).within(300).fails(Outcome.NeedsAlter.class, o -> {
            if (o.level() != RouteSpec.Alter.ANY) {
                throw new GameTestAssertException("应当是放宽到 any 才有路:" + o);
            }
        });
    }

    /**
     * 关在没有顶的泥土围栏里(墙三格高,跳不出去),泥土许可答"要问",身上没有料:出去要么挖墙(要主人同意),要么垫柱翻过墙
     * (要料)。只许改自然地形时,结局是"放宽到 any 才有路"——许改的不够先报,不说成没料。旁边一模一样的围栏里,带着圆石的
     * 身体垫柱翻出去,墙一格不挖:这个场景里料确实是另一条出路。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void asks_for_consent_before_materials(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 8, 3, 8, Blocks.DIRT);
        t.fill(3, 1, 3, 7, 3, 7, Blocks.AIR);
        t.fill(2, 1, 17, 8, 3, 23, Blocks.DIRT);
        t.fill(3, 1, 18, 7, 3, 22, Blocks.AIR);
        t.terrain = (change, pos, state, view) -> state.is(Blocks.DIRT) ? Permit.ask("pen") : Permit.ALLOW;
        TestBody empty = t.body(5, 1, 5);
        t.go(empty, Goals.at(t.at(12, 1, 5)), NATURAL).within(300).fails(Outcome.NeedsAlter.class, o -> {
            if (o.level() != RouteSpec.Alter.ANY) {
                throw new GameTestAssertException("应当是放宽到 any 才有路:" + o);
            }
        }).then(Scenes::unaltered);
        TestBody carrying = t.body(5, 1, 20);
        Trial.give(carrying, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(carrying, Blocks.COBBLESTONE);
        t.go(carrying, Goals.at(t.at(12, 1, 20)), NATURAL).within(600).arrives().then(r -> {
            if (!dug(r).isEmpty() || placed(r).isEmpty()) {
                throw new GameTestAssertException("应当垫柱翻出去、不挖墙:" + r.report.ledger().entries());
            }
        });
    }

    // ==================== 改地形补充 ====================

    /** 两格高的泥土墙,口在六格多的绕路之外:许改地形也不挖,绕过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void detours_rather_than_dig_a_short_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 2, Blocks.DIRT, 10);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(11, 1, 5)), NATURAL).within(400).arrives().then(Scenes::unaltered);
    }

    /**
     * 一道一格宽的沟,沟里是一层薄雪、雪下是岩浆块(站不得):搭桥时那块放进薄雪那一格,把雪顶掉;许可问的、账上记的、
     * 要同意的都是那一格。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void places_into_the_snow_layer_it_bridges_over(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int z = 0; z <= 39; z++) {
            t.set(8, -1, z, Blocks.MAGMA_BLOCK);
            t.set(8, 0, z, Blocks.SNOW);
        }
        BlockPos snowRow = t.at(8, 0, 0);
        List<BlockPos> asked = new ArrayList<>();
        t.terrain = (change, pos, state, view) -> {
            if (change instanceof TerrainPolicy.Change.Place && pos.getX() == snowRow.getX() && pos.getY() == snowRow.getY()) {
                return Permit.ask("snow");
            }
            return pos.getX() == snowRow.getX() && change instanceof TerrainPolicy.Change.Place
                    ? Permit.deny("not the snow cell") : Permit.ALLOW;
        };
        TestBody body = t.body(4, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(12, 1, 5)), ANY).within(400).arrives().then(r -> {
            List<EditLedger.Placed> placed = placed(r);
            if (placed.size() != 1 || placed.get(0).pos().getY() != snowRow.getY()
                    || !placed.get(0).before().is(Blocks.SNOW) || !(placed.get(0).permit() instanceof Permit.Ask)) {
                throw new GameTestAssertException("应当把块放进薄雪那一格:" + placed);
            }
            List<Bill.Consent> consents = r.report.bill().consents();
            if (consents.size() != 1 || !consents.get(0).pos().equals(placed.get(0).pos())) {
                throw new GameTestAssertException("要同意的应当是薄雪那一格:" + consents);
            }
        });
    }

    /**
     * 一整块横贯场地的泥土(顶上压着基岩,翻不上去、绕不过去),身体站在里面挖出的一个小龛里,去处在同一层的另一头:一路挖
     * 隧道过去,挖掉的从来不是那一刻唯一托着身体的那一格。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1200)
    public static void never_digs_the_block_holding_it_up(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 0, 0, 14, 2, 39, Blocks.DIRT);
        t.fill(2, 3, 0, 14, 3, 39, Blocks.BEDROCK);
        t.fill(3, 1, 5, 3, 2, 5, Blocks.AIR);
        TestBody body = t.body(3, 1, 5);
        List<String> violations = new ArrayList<>();
        t.hands = hands -> new Effector() {
            @Override
            public Strike dig(BlockHitResult hit) {
                Set<BlockPos> supports = Supports.of(body);
                Strike strike = hands.dig(hit);
                if (strike instanceof Strike.Broke broke && supports.equals(Set.of(broke.pos()))) {
                    violations.add(t.rel(broke.pos()));
                }
                return strike;
            }

            @Override
            public void release() {
                hands.release();
            }

            @Override
            public Use use(BlockHitResult hit) {
                return hands.use(hit);
            }
        };
        t.go(body, Goals.at(t.at(11, 1, 5)), NATURAL).within(1100).arrives().then(r -> {
            if (dug(r).size() < 16) {
                throw new GameTestAssertException("应当挖一条隧道过去:" + dug(r));
            }
            if (!violations.isEmpty()) {
                throw new GameTestAssertException("挖掉了唯一托着身体的那一格:" + violations);
            }
        });
    }

    /** 关在泥土小屋里,改动预算只有一格:结局是"改动预算不够",最便宜那条要改两格,世界一格不变。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reports_how_many_alterations_the_cheapest_way_needs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        hut(t);
        TestBody body = t.body(5, 1, 5);
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL.edit().alterBudget(1).build()).within(300)
                .fails(Outcome.OverAlterBudget.class, o -> {
                    if (o.needed() != 2) {
                        throw new GameTestAssertException("最便宜那条要改两格:" + o);
                    }
                }).then(Scenes::unaltered);
    }

    /** 泥土墙横贯场地,正对着的一段禁挖泥土(建造规格的按种类禁挖),远处一段是木板:去挖木板那一段,一块泥土不挖。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void keeps_the_block_bans_of_the_spec(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT, -1);
        t.fill(8, 1, 14, 8, 3, 16, Blocks.OAK_PLANKS);
        TestBody body = t.body(4, 1, 5);
        RouteSpec spec = NATURAL.edit().bans(new BlockBans(Set.of(Blocks.DIRT), Set.of(), Set.of())).build();
        t.go(body, Goals.at(t.at(12, 1, 5)), spec).within(700).arrives().then(r -> {
            if (dug(r).isEmpty() || dug(r).stream().anyMatch(d -> d.before().is(Blocks.DIRT))) {
                throw new GameTestAssertException("应当只挖木板:" + dug(r));
            }
        });
    }

    /** 要去用的工作台嵌在一道石墙里:许改地形,走到够得着、看得见它的地方就停,工作台一直在。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void does_not_dig_the_table_it_goes_to_use(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.STONE, -1);
        t.set(8, 1, 5, Blocks.CRAFTING_TABLE);
        TestBody body = t.body(2, 1, 5);
        t.go(body, Goals.reach(t.at(8, 1, 5), com.dwinovo.numen.pathing.body.Snapshots.of(body).stats()), NATURAL)
                .within(400).arrives().then(r -> {
                    if (!t.state(8, 1, 5).is(Blocks.CRAFTING_TABLE) || !dug(r).isEmpty()) {
                        throw new GameTestAssertException("动了要去用的工作台:" + r.report.ledger().entries());
                    }
                });
    }

    // ==================== 世界边界 ====================

    private static double borderX;
    private static double borderZ;
    private static double borderSize;

    @BeforeBatch(batch = BORDER)
    public static void settleBorder(ServerLevel level) {
        Worlds.settle(level);
        WorldBorder border = level.getWorldBorder();
        borderX = border.getCenterX();
        borderZ = border.getCenterZ();
        borderSize = border.getSize();
    }

    @AfterBatch(batch = BORDER)
    public static void restoreBorder(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        border.setCenter(borderX, borderZ);
        border.setSize(borderSize);
    }

    /**
     * 世界边界从沟边那一排格子中间穿过:身体站得进那一排(身子在界内),那一排的格子却不在界内,原版不许在那儿挖、放。
     * 去处就在那一排上:桥搭在界内的下一排,一块也不往边界上那一排放。
     */
    @GameTest(template = ARENA, batch = BORDER, timeoutTicks = 900)
    public static void does_not_place_on_the_world_border(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ditch(t, 10, 13);
        BlockPos row = t.at(0, 0, 2);
        double half = 1000;
        WorldBorder border = t.level.getWorldBorder();
        border.setCenter(row.getX() + 20, row.getZ() + 0.15 + half);
        border.setSize(half * 2);
        TestBody body = t.body(6, 5, 2);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 2)), NATURAL).within(800).arrives().then(r -> {
            for (EditLedger.Entry e : r.report.ledger().entries()) {
                if (e.pos().getZ() <= row.getZ()) {
                    throw new GameTestAssertException("在边界上那一排动了手:" + t.rel(e.pos()));
                }
            }
        });
    }

    /** 此刻托着身体的方块:脚底往下一薄层里碰得到碰撞形状的那几格。 */
    static final class Supports {
        private Supports() {}

        static Set<BlockPos> of(TestBody body) {
            var box = body.getBoundingBox();
            var sole = new net.minecraft.world.phys.AABB(box.minX, box.minY - 0.1, box.minZ, box.maxX, box.minY,
                    box.maxZ);
            Set<BlockPos> out = new java.util.HashSet<>();
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sole.minX, sole.minY, sole.minZ),
                    BlockPos.containing(sole.maxX, sole.maxY, sole.maxZ))) {
                BlockState state = body.level().getBlockState(pos);
                for (var piece : state.getCollisionShape(body.level(), pos).toAabbs()) {
                    if (piece.move(pos).intersects(sole)) {
                        out.add(pos.immutable());
                        break;
                    }
                }
            }
            return out;
        }
    }
}
