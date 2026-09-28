package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.body.PlayerHands;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.DigTime;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 挖掘:头顶是沙子沙砾、挖了会漏水漏岩浆、冰,都不挖;挑对工具;空手挖原木、水下挖掘的耗时与原版、与定价一致;背包深处的好工具
 * 被计价也被用上;红石矿、修补附魔都不让挖掘重开;隧道里每一格只开挖一次;实体挡住准星时停手不打它;破坏事件被取消时以
 * 拒绝收场;慢挖硬方块不被当成卡住;创造模式五格交互距离。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class DigGameTests {

    private static final String BATCH = "pathing_dig";

    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 横贯场地的一道墙:{@code x} 那一列,y = 1..{@code height} 都是 {@code block}。 */
    private static void wall(Trial t, int x, int height, Block block) {
        t.fill(x, 1, 0, x, height, 39, block);
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

    /** 挖掉的格都在 {@code z >= minZ} 那一段(相对场地)。 */
    private static Consumer<Trial.Run> dugOnlyFrom(Trial t, int minZ) {
        return r -> {
            if (dug(r).isEmpty()) {
                throw new GameTestAssertException("一格也没挖");
            }
            for (EditLedger.Dug d : dug(r)) {
                if (d.pos().getZ() - t.origin.getZ() < minZ) {
                    throw new GameTestAssertException("挖了不该挖的那一段:" + t.rel(d.pos()) + " " + d.before());
                }
            }
        };
    }

    /** 每一格从开挖到碎用的刻数与原版一致(差一刻以内),与规划定价用的 {@link DigTime} 也一致。 */
    private static void vanillaTimed(DigWatch watch, TestBody body) {
        if (watch.broken().isEmpty()) {
            throw new GameTestAssertException("一格也没挖碎");
        }
        for (DigWatch.Dig d : watch.broken()) {
            int priced = DigTime.ticks(Snapshots.of(body), d.tool, d.state, d.eyeInWater, d.grounded);
            if (Math.abs(d.ticks() - d.vanillaTicks()) > 1 || Math.abs(priced - d.vanillaTicks()) > 1) {
                throw new GameTestAssertException("耗时对不上:" + d + " 定价 " + priced + " 全部 " + watch.broken());
            }
        }
    }

    /**
     * 三格高的泥土墙横贯场地,手上木锹,许改自然地形:挖开身体高的两格穿过去。上一格碎了之后手要缓几刻(原版客户端的
     * {@code destroyDelay})才开挖下一格,缓的正是规划给挖一格定价时加上的那几刻({@link DigTime#cooldown})。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void waits_out_the_vanilla_cooldown_between_two_digs(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.WOODEN_SHOVEL));
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(400).arrives().then(r -> {
            List<DigWatch.Dig> broken = new ArrayList<>(watch.broken());
            broken.sort(java.util.Comparator.comparingLong(d -> d.startedAt));
            if (broken.size() < 2) {
                throw new GameTestAssertException("应当连着挖两格:" + broken);
            }
            int cooldown = DigTime.cooldown(false, false);
            for (int i = 1; i < broken.size(); i++) {
                long idle = broken.get(i).startedAt - broken.get(i - 1).brokeAt;
                if (idle <= cooldown) {
                    throw new GameTestAssertException("上一格碎了 " + idle + " 刻就开挖下一格,没缓够 " + cooldown + " 刻:" + broken);
                }
            }
            vanillaTimed(watch, body);
        });
    }

    // ==================== 挖掘的物理约束 ====================

    /**
     * 墙顶一段压着沙子、一段压着沙砾:挖哪一格的时候它正上方都不是沙子沙砾(要么先把上面的挖掉,要么挖顶上是泥土的那一段),
     * 沙子沙砾一粒没自己掉下来。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void does_not_dig_under_sand_or_gravel(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT);
        t.fill(8, 3, 0, 8, 3, 10, Blocks.SAND);
        t.fill(8, 3, 11, 8, 3, 20, Blocks.GRAVEL);
        TestBody body = t.body(4, 1, 8);
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 8)), NATURAL).within(800).arrives().then(r -> {
            for (DigWatch.Dig d : watch.broken()) {
                if (d.above.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
                    throw new GameTestAssertException("挖的格正上方压着会掉的方块:" + d + " 上面 " + d.above);
                }
            }
        });
    }

    /**
     * 三格厚的泥土墙,墙心一段埋着一溜水、一段埋着一溜岩浆:挖哪一格的时候它上面与四周都没有液体,一滴没漏出来(实际账
     * 对得上世界)。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1200)
    public static void does_not_dig_where_it_would_leak(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, 1, 0, 9, 3, 39, Blocks.DIRT);
        t.fill(8, 1, 0, 8, 2, 12, Blocks.WATER);
        t.fill(8, 1, 14, 8, 2, 26, Blocks.LAVA);
        TestBody body = t.body(4, 1, 13);
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 13)), NATURAL).within(1100).arrives().then(r -> {
            if (watch.broken().isEmpty()) {
                throw new GameTestAssertException("一格也没挖");
            }
            for (DigWatch.Dig d : watch.broken()) {
                if (d.touched(state -> !state.getFluidState().isEmpty())) {
                    throw new GameTestAssertException("挖了挨着液体的格:" + d);
                }
            }
        });
    }

    /** 墙的一段是冰(挖了会化成水),一段是泥土:去挖泥土那一段。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void does_not_dig_ice(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT);
        t.fill(8, 1, 0, 8, 3, 20, Blocks.ICE);
        TestBody body = t.body(4, 1, 8);
        t.go(body, Goals.at(t.at(12, 1, 8)), NATURAL).within(800).arrives().then(dugOnlyFrom(t, 21));
    }

    /** 墙下层是石头、上层是泥土,手上拿着剑,快捷栏里有木镐、木锹:石头用镐挖,泥土用锹挖。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void picks_the_right_tool(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT);
        t.fill(8, 1, 0, 8, 1, 39, Blocks.STONE);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        body.getInventory().setItem(1, new ItemStack(Items.WOODEN_PICKAXE));
        body.getInventory().setItem(2, new ItemStack(Items.WOODEN_SHOVEL));
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(500).arrives().then(r -> {
            for (DigWatch.Dig d : watch.broken()) {
                boolean right = d.state.is(Blocks.STONE) ? d.toolAtBreak.is(Items.WOODEN_PICKAXE)
                        : d.toolAtBreak.is(Items.WOODEN_SHOVEL);
                if (!right) {
                    throw new GameTestAssertException("工具挑错了:" + d + " 碎的时候手上是 " + d.toolAtBreak);
                }
            }
            vanillaTimed(watch, body);
        });
    }

    // ==================== 挖掘补充 ====================

    /** 原木墙,手上空着:每一块从开挖到碎的刻数与原版空手挖原木一样,规划的定价也一样。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void digs_a_log_bare_handed_in_vanilla_time(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.OAK_LOG);
        TestBody body = t.body(4, 1, 5);
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(600).arrives().then(r -> {
            vanillaTimed(watch, body);
            watch.eachStartedOnce();
        });
    }

    /**
     * 浮在两格深的池子里,上岸那一面的岸边压着两格泥土(别的三面是石墙),手上一把铁锹:浮在水里把岸边挖开再上去。规划时给
     * 每一格定下的"眼睛在不在水里、脚着不着地"与挖的那一刻一样,耗时与原版、与定价一致。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1200)
    public static void digs_from_the_water_as_priced(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(2, 1, 2, 7, 4, 8, Blocks.STONE);
        t.fill(3, 1, 3, 7, 2, 7, Blocks.WATER);
        t.fill(3, 3, 3, 7, 4, 7, Blocks.AIR);
        t.fill(8, 1, 0, 39, 2, 39, Blocks.STONE);
        t.fill(8, 3, 2, 8, 4, 8, Blocks.DIRT);
        TestBody body = t.body(5, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_SHOVEL));
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        NavRequest request = NavRequest.to(Goals.at(t.at(11, 3, 5)), NATURAL);
        t.plan(body, PlanQuery.of(request.goal(), request.spec(), 1), plan -> {
            if (plan.candidates().isEmpty()) {
                throw new GameTestAssertException("没规划出路:" + plan.outcome());
            }
            Route route = plan.candidates().get(0).route();
            List<Edit.Dig> planned = new ArrayList<>();
            for (Edit e : route.edits()) {
                if (e instanceof Edit.Dig d) {
                    planned.add(d);
                }
            }
            if (planned.isEmpty()) {
                throw new GameTestAssertException("规划的路不挖岸边:" + route);
            }
            t.go(body, request.following(route)).within(1000).arrives().then(r -> {
                vanillaTimed(watch, body);
                for (Edit.Dig p : planned) {
                    DigWatch.Dig d = watch.broken().stream().filter(b -> b.pos.equals(p.pos())).findFirst()
                            .orElseThrow(() -> new GameTestAssertException("规划要挖的 " + t.rel(p.pos()) + " 没挖"));
                    if (d.eyeInWater != p.eyeInWater() || d.grounded != p.grounded()) {
                        throw new GameTestAssertException("定价的前提与挖的那一刻不一样:规划 " + p + ",实际 " + d);
                    }
                }
            });
        });
    }

    /**
     * 石墙只在远处留了个口,铁镐在背包深处:空手挖石头比绕远路贵,拿着铁镐挖比绕路便宜——规划把铁镐算进了价钱,于是挖墙;
     * 执行时中键把铁镐换到手上用,记进身体动作。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void prices_and_uses_a_better_tool_deep_in_the_inventory(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(8, 1, 0, 8, 3, 30, Blocks.STONE);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(20, new ItemStack(Items.IRON_PICKAXE));
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(500).arrives().then(r -> {
            if (dug(r).isEmpty()) {
                throw new GameTestAssertException("绕路去了,铁镐没算进价钱");
            }
            boolean held = r.report.actions().stream().anyMatch(a -> a instanceof BodyAction.Held h
                    && h.item() == Items.IRON_PICKAXE && h.from() == 20);
            if (!held) {
                throw new GameTestAssertException("铁镐没从背包深处拿到手上:" + r.report.actions());
            }
        });
    }

    /**
     * 选中的快捷栏格空着,背包里有一把铁镐:泥土墙空手挖不比镐子慢,就空手挖,手里一直空着——从一个空格换到另一个空格
     * 手上拿的没变,不是身体动作,结局里一个身体动作也没有。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void digging_bare_handed_from_an_empty_slot_is_no_body_action(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.DIRT);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(3, new ItemStack(Items.IRON_PICKAXE));
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(400).arrives().then(r -> {
            if (dug(r).isEmpty()) {
                throw new GameTestAssertException("一格也没挖");
            }
            if (!r.report.actions().isEmpty()) {
                throw new GameTestAssertException("空手挖不该有身体动作:" + r.report.actions());
            }
        });
    }

    /** 红石矿墙,手上铁镐:红石矿被敲一下会亮起来(方块状态变了),挖掘照样一次挖完,不重开。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void does_not_restart_on_redstone_ore(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.REDSTONE_ORE);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(500).arrives().then(r -> {
            vanillaTimed(watch, body);
            watch.eachStartedOnce();
        });
    }

    /** 黑曜石墙,手上一把带修补的旧钻石镐,挖的时候身边一直有经验球:镐子被修好了一些,挖掘照样一次挖完,不当成换了工具重开。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void mending_does_not_restart_the_dig(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.OBSIDIAN);
        TestBody body = t.body(4, 1, 5);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        Holder<Enchantment> mending = t.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.MENDING);
        pickaxe.enchant(mending, 1);
        pickaxe.set(DataComponents.DAMAGE, 1000);
        body.getInventory().setItem(0, pickaxe);
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(800)
                .during(r -> {
                    if (watch.digging() && r.ticks % 5 == 0) {
                        ExperienceOrb.award(t.level, r.body.position(), 3);
                    }
                })
                .arrives().then(r -> {
                    vanillaTimed(watch, body);
                    watch.eachStartedOnce();
                    if (body.getInventory().getItem(0).getDamageValue() >= 1000) {
                        throw new GameTestAssertException("镐子没被修过,场景没起作用");
                    }
                });
    }

    /** 六格高的石头山,穿过去是四格长的隧道,手上铁镐:每一格只开挖一次,准星不在两格之间来回换靶。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void digs_each_block_of_a_tunnel_once(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(7, 1, 0, 10, 6, 39, Blocks.STONE);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(500).arrives().then(r -> {
            if (watch.broken().size() != 8) {
                throw new GameTestAssertException("应当挖开八格:" + watch.broken());
            }
            watch.eachStartedOnce();
        });
    }

    /**
     * 撤回桥上的块时,正挖着,一头猪挡到了眼睛与那一块之间:准星落在猪身上,手停下,不打猪;猪走开之后接着挖完,四块都撤掉。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1500)
    public static void pauses_while_a_mob_blocks_the_crosshair(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(14, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(6, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        DigWatch watch = new DigWatch(body);
        t.hands = watch::wrap;
        Pig[] pig = {null};
        int[] shownAt = {-1};
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL.edit().takeBack(true).build()).within(1400).arrives()
                .during(r -> {
                    if (r.teardown == null) {
                        return;
                    }
                    if (pig[0] == null && watch.digging()) {
                        pig[0] = EntityType.PIG.create(t.level);
                        pig[0].setNoAi(true);
                        BlockPos at = t.at(15, 5, 5);
                        pig[0].moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                        t.level.addFreshEntity(pig[0]);
                        shownAt[0] = r.ticks;
                    } else if (pig[0] != null && !pig[0].isRemoved() && r.ticks - shownAt[0] >= 30) {
                        if (pig[0].getHealth() < pig[0].getMaxHealth() || pig[0].getLastHurtByMob() != null) {
                            throw new GameTestAssertException("打了挡在准星上的猪");
                        }
                        pig[0].discard();
                    }
                })
                .takesBack(RouteSpec.defaults(), r -> {
                    if (pig[0] == null || !pig[0].isRemoved()) {
                        throw new GameTestAssertException("猪没挡上,场景没起作用");
                    }
                    if (r.teardown.taken().size() != 4 || !r.teardown.left().isEmpty()) {
                        throw new GameTestAssertException("应当撤掉桥上四块:" + r.teardown);
                    }
                });
    }

    /** 石墙,手上铁镐,别的模组把这具身体的破坏事件都取消了:以"拒绝"收场,点出那一格与拒绝的理由,不空挥到超时。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 500)
    public static void refuses_when_the_break_event_is_cancelled(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.STONE);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        Consumer<BlockEvent.BreakEvent> cancel = event -> {
            if (event.getPlayer() == body) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(cancel);
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(300)
                .fails(Outcome.Denied.class, o -> {
                    NeoForge.EVENT_BUS.unregister(cancel);
                    if (o.reason() != PlayerHands.Refusal.SERVER || o.cell().getX() - t.origin.getX() != 8) {
                        throw new GameTestAssertException("应当以服务端不让挖收场、点出墙上那一格:" + o);
                    }
                })
                .then(Scenes::unaltered);
    }

    /** 黑曜石墙,手上钻石镐,一块要挖近两百刻:一路对外都是"在推进",不被当成卡住。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 900)
    public static void slow_hard_blocks_do_not_look_stuck(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        wall(t, 8, 3, Blocks.OBSIDIAN);
        TestBody body = t.body(4, 1, 5);
        body.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        t.go(body, Goals.at(t.at(12, 1, 5)), NATURAL).within(800)
                .during(r -> {
                    if (r.ticks > 5 && !r.navigation.progressing()) {
                        throw new GameTestAssertException("挖黑曜石途中不在推进了(第 " + r.ticks + " 刻)");
                    }
                })
                .arrives();
    }

    /** 创造模式交互距离五格(原版在玩家的刻里把创造模式的加成挂到属性上):去够一块方块,停在四格半之外、五格之内就够着、看见了。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reaches_five_blocks_in_creative(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.set(10, 1, 5, Blocks.STONE);
        TestBody body = t.body(2, 1, 5);
        body.setGameMode(GameType.CREATIVE);
        BlockPos target = t.at(10, 1, 5);
        t.later(2, () -> t.go(body, Goals.reach(target, Snapshots.of(body).stats()), RouteSpec.defaults())
                .within(300).arrives().then(r -> {
                    double distance = Math.sqrt(new AABB(target).distanceToSqr(r.body.getEyePosition()));
                    if (distance <= 4.5 || distance >= 5) {
                        throw new GameTestAssertException("应当停在四格半到五格之间:" + distance);
                    }
                }));
    }
}
