package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.api.entity.Mouse;
import static com.dwinovo.numen.pathing.gametest.Scenes.dug;
import static com.dwinovo.numen.pathing.gametest.Scenes.placed;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 许改地形时挖穿、垫柱、搭桥、向下挖;桥位上有单层雪时块放进那一格;不挖托着自己的那一格。
 */
public class AlterGameTests {

    private static final String BATCH = "pathing_alter";

    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();
    private static final RouteSpec ANY = RouteSpec.defaults().edit().changes(true).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 高出地面四格、中间隔着 {@code x0..x1} 一道沟的两块基岩台子:沟挖不动,跳下去也摔不起,只能搭桥。 */
    private static void ditch(Trial t, int x0, int x1) {
        t.fill(0, 1, 0, x0 - 1, 4, 39, Blocks.BEDROCK);
        t.fill(x1 + 1, 1, 0, 39, 4, 39, Blocks.BEDROCK);
    }

    // ==================== 不许改地形 ====================

    // ==================== 改地形 ====================

    /** 四格高的石崖,身上有圆石:垫块上去(上四格要垫三块,最后一下跳上崖),不去空手刨石头。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 390)
    public static void pillars_up_a_cliff(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 39, 4, 39, Blocks.STONE);
        TestBody body = t.body(5, 1, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(14, 5, 5)), NATURAL).within(380).arrives().then(r -> {
            if (!dug(r).isEmpty() || placed(r).size() < 3) {
                throw Checks.failure("应当垫柱上去、不挖:" + r.report.ledger().entries());
            }
        });
    }

    /** 两块基岩台子之间一道四格宽的沟:身上有圆石,在台面那一层搭四格桥过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 423)
    public static void bridges_a_ditch(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        ditch(t, 10, 13);
        TestBody body = t.body(6, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(413).arrives().then(r -> {
            List<EditLedger.Placed> placed = placed(r);
            if (placed.size() != 4 || placed.stream().anyMatch(p -> p.pos().getY() - t.origin.getY() != 4)) {
                throw Checks.failure("应当在台面那一层搭四格桥:" + placed);
            }
        });
    }

    /**
     * 站在一根四格高的基岩柱顶,去处在柱子旁边一格、脚下是空的:桥位只有两个能点的邻面——脚下那块朝桥位的侧面(站着点不到,
     * 要潜行探出边沿才看得见)和东边一块打开的活板门的板面(被起步那一列东边的石头挡住,看不见)。站定点不中,探出去点得中:
     * 规划认出这一步要潜行,执行潜行探出去放,桥搭得成;规划若只看有没有能点的面,会认作站着就能放,执行点不中,反复走不下去。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 213)
    public static void bridges_leaning_out_when_only_the_back_face_is_in_sight(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(5, 1, 5, 5, 4, 5, Blocks.BEDROCK);
        t.set(6, 4, 4, Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, true)
                .setValue(net.minecraft.world.level.block.TrapDoorBlock.FACING, net.minecraft.core.Direction.WEST));
        t.set(6, 5, 5, Blocks.STONE);
        TestBody body = t.body(5, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(5, 5, 4)), NATURAL).within(203).arrives().then(r -> {
            List<EditLedger.Placed> placed = placed(r);
            if (placed.size() != 1 || !placed.get(0).pos().equals(t.at(5, 4, 4))) {
                throw Checks.failure("应当只在桥位放一块:" + placed);
            }
        });
    }

    /** 站在四格厚的泥土上,去处在正下方的地面:向下挖下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 450)
    public static void digs_down_to_a_lower_goal(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 10, 4, 10, Blocks.DIRT);
        TestBody body = t.body(5, 5, 5);
        t.go(body, Goals.at(t.at(5, 1, 5)), NATURAL).within(440).arrives().then(r -> {
            if (dug(r).size() < 4) {
                throw Checks.failure("应当挖下去四格:" + dug(r));
            }
        });
    }

    /**
     * 站着的那一格里长着一株草(没有碰撞、有轮廓):准星从眼睛往下看先碰上它,要挖的脚下那一格点不中。向下挖之前先把这株草打掉,
     * 再挖下面的泥土,一路落到底。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 450)
    public static void digs_down_through_the_grass_underfoot(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 10, 4, 10, Blocks.DIRT);
        t.set(5, 5, 5, Blocks.SHORT_GRASS);
        TestBody body = t.body(5, 5, 5);
        t.go(body, Goals.at(t.at(5, 1, 5)), NATURAL).within(440).arrives().then(r -> {
            if (dug(r).size() < 5 || !dug(r).get(0).pos().equals(t.at(5, 5, 5))) {
                throw Checks.failure("应当先打掉脚下那株草再往下挖四格:" + dug(r));
            }
            if (r.report.journal().blockages() > 0) {
                throw Checks.failure("被脚下的草挡着走不通:" + r.report.journal().replans());
            }
        });
    }

    /**
     * 两座高台隔着一道深坑(深到跳不下去),身上只有圆石,快捷栏塞满、主手拿着圆石:先在坑上垫一块走过去,紧接着在对面往下挖三格,
     * 落进藏在泥土里的空腔。没有空手可换,就握着圆石挖泥土;往下挖的头一格不为此卡住。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void digs_down_right_after_bridging_a_gap(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 3, 4, 7, 7, Blocks.STONE);
        t.fill(6, 1, 3, 9, 7, 7, Blocks.DIRT);
        t.fill(8, 3, 5, 8, 4, 5, Blocks.AIR);
        TestBody body = t.body(3, 8, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16), new ItemStack(Items.STICK), new ItemStack(Items.APPLE),
                new ItemStack(Items.BREAD), new ItemStack(Items.COAL), new ItemStack(Items.CLAY_BALL), new ItemStack(Items.FEATHER),
                new ItemStack(Items.FLINT), new ItemStack(Items.STRING));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(8, 3, 5)), NATURAL).within(590).arrives().then(r -> {
            if (placed(r).isEmpty() || dug(r).size() < 3) {
                throw Checks.failure("应当先垫后挖:放 " + placed(r) + ",挖 " + dug(r));
            }
            if (r.report.journal().blockages() > 0) {
                throw Checks.failure("垫完紧接着往下挖走不通:" + r.report.journal().replans());
            }
        });
    }


    // ==================== 改地形补充 ====================

    /**
     * 一道一格宽的沟,沟里是一层薄雪、雪下是岩浆块(站不得):搭桥时那块放进薄雪那一格,把雪顶掉;许可问的、账上记的、
     * 要同意的都是那一格。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 288)
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
        t.go(body, Goals.at(t.at(12, 1, 5)), ANY).within(278).arrives().then(r -> {
            List<EditLedger.Placed> placed = placed(r);
            if (placed.size() != 1 || placed.get(0).pos().getY() != snowRow.getY() || !placed.get(0).before().is(Blocks.SNOW)) {
                throw Checks.failure("应当把块放进薄雪那一格:" + placed);
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
        body.mouse(new Mouse(body) {
            @Override
            public Strike dig() {
                Set<BlockPos> supports = Supports.of(body);
                Strike strike = super.dig();
                if (strike instanceof Strike.Broke broke && supports.equals(Set.of(broke.pos()))) {
                    violations.add(t.rel(broke.pos()));
                }
                return strike;
            }
        });
        t.go(body, Goals.at(t.at(11, 1, 5)), NATURAL).within(1100).arrives().then(r -> {
            if (dug(r).size() < 16) {
                throw Checks.failure("应当挖一条隧道过去:" + dug(r));
            }
            if (!violations.isEmpty()) {
                throw Checks.failure("挖掉了唯一托着身体的那一格:" + violations);
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
