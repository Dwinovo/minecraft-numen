package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.Optional;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.body.BodyAction;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 垫路料:没料报"没有料"、不许改地形时不提料;创造模式凭空取料、按清单变料;按清单优先级取料、副手回退、料只在背包深处
 * 而宿主不许动背包;搭桥后手上的武器还在;事后撤回路上垫的块,托着自己的先挪开再撤。
 *
 * <p>场景都是两块基岩台子中间一道四格宽的沟(沟挖不动、跳下去摔不起),过沟只能搭桥。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public class MaterialGameTests {

    private static final String BATCH = "pathing_materials";

    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 沟在 x = 10..13,台面在 y = 4,身体站在 y = 5。 */
    private static Trial ditch(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 9, 4, 39, Blocks.BEDROCK);
        t.fill(14, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        return t;
    }

    /** 路上放下的块都是 {@code block}。 */
    private static void placedAre(Trial.Run r, Block block) {
        boolean any = false;
        for (EditLedger.Entry e : r.report.ledger().entries()) {
            if (e instanceof EditLedger.Placed p) {
                any = true;
                if (!p.after().is(block)) {
                    throw new GameTestAssertException("应当放 " + block + ",却放了 " + p.after() + " 在 " + p.pos());
                }
            }
        }
        if (!any) {
            throw new GameTestAssertException("一块也没放");
        }
    }

    /** 身上一块料也没有:结局是"没有料",世界一格不变。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void reports_no_materials(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(300).fails(Outcome.NoMaterials.class)
                .then(Scenes::unaltered);
    }

    /** 不许改地形时,身上有料没料,结局都是"许改地形才有路",不提料。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void may_not_alter_says_nothing_about_materials(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody carrying = t.body(6, 5, 5);
        Trial.give(carrying, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(carrying, Blocks.COBBLESTONE);
        t.go(carrying, Goals.at(t.at(17, 5, 5)), RouteSpec.defaults()).within(300).fails(Outcome.NeedsAlter.class);
        TestBody empty = t.body(6, 5, 20);
        t.materials = Materials.NONE;
        t.go(empty, Goals.at(t.at(17, 5, 20)), RouteSpec.defaults()).within(300).fails(Outcome.NeedsAlter.class);
    }

    /** 创造模式、背包空着:照料清单的第一种凭空取一叠搭桥,身体动作里记下取料。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void conjures_materials_in_creative_by_the_list(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.setGameMode(GameType.CREATIVE);
        t.materials = Trial.carried(body, Blocks.OAK_PLANKS, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> {
            placedAre(r, Blocks.OAK_PLANKS);
            boolean conjured = r.report.actions().stream().anyMatch(a -> a instanceof BodyAction.Conjured c
                    && c.item() == Items.OAK_PLANKS);
            if (!conjured) {
                throw new GameTestAssertException("凭空取料没记进身体动作:" + r.report.actions());
            }
        });
    }

    /** 快捷栏里有圆石、背包深处有泥土,料清单泥土在前:按清单拿泥土垫。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void takes_materials_in_list_order(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        body.getInventory().setItem(20, new ItemStack(Items.DIRT, 16));
        t.materials = Trial.carried(body, Blocks.DIRT, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> placedAre(r, Blocks.DIRT));
    }

    /** 料只在副手,主手拿着剑:主副手一换就垫,垫完剑还在身上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void falls_back_to_the_offhand(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        body.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> {
            placedAre(r, Blocks.COBBLESTONE);
            stillHasSword(r);
        });
    }

    /** 主手拿着一叠木板(不在料清单上),圆石只在副手:主副手一换,桥全用圆石搭,一块木板也不放。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void bridges_with_the_listed_material_not_what_is_in_hand(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_PLANKS, 16));
        body.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> placedAre(r, Blocks.COBBLESTONE));
    }

    /** 快捷栏第 1 格与第 4 格都是圆石:用的是靠前的第 1 格,第 4 格一块没少。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void takes_the_material_from_the_earlier_slot(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 16));
        body.getInventory().setItem(4, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> {
            placedAre(r, Blocks.COBBLESTONE);
            int earlier = body.getInventory().getItem(1).getCount();
            int later = body.getInventory().getItem(4).getCount();
            if (earlier >= 16 || later != 16) {
                throw new GameTestAssertException("应当从第 1 格取料:第 1 格剩 " + earlier + ",第 4 格剩 " + later);
            }
        });
    }

    /** 手上拿着剑,圆石在背包深处:中键把圆石换进快捷栏搭桥,剑换回背包,一直在身上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void keeps_the_weapon_after_bridging(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            body.getInventory().setItem(slot, new ItemStack(Items.IRON_SWORD));
        }
        body.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(700).arrives().then(r -> {
            placedAre(r, Blocks.COBBLESTONE);
            int swords = 0;
            for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
                if (body.getInventory().getItem(i).is(Items.IRON_SWORD)) {
                    swords++;
                }
            }
            if (swords != Inventory.getSelectionSize()) {
                throw new GameTestAssertException("剑少了:还剩 " + swords + " 把");
            }
        });
    }

    private static void stillHasSword(Trial.Run r) {
        if (!r.body.getInventory().contains(new ItemStack(Items.IRON_SWORD))) {
            throw new GameTestAssertException("手上原来的剑不见了");
        }
    }

    /** 圆石只在背包深处,宿主的料端口只认快捷栏(不许动背包):结局是"没有料"。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void deep_inventory_only_is_no_materials_when_the_host_says_so(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = () -> {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (body.getInventory().getItem(slot).is(Items.COBBLESTONE)) {
                    return Optional.of(Blocks.COBBLESTONE);
                }
            }
            return Optional.empty();
        };
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(300).fails(Outcome.NoMaterials.class)
                .then(Scenes::unaltered);
    }

    /** 搭桥过沟,到了之后撤回:桥上四块都挖掉,一块不留。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1500)
    public static void takes_back_the_bridge(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL.edit().takeBack(true).build()).within(1400).arrives()
                .takesBack(RouteSpec.defaults(), r -> {
                    if (r.teardown.taken().size() != 4 || !r.teardown.left().isEmpty()) {
                        throw new GameTestAssertException("应当撤掉桥上四块:" + r.teardown);
                    }
                });
    }

    /** 去处在桥的半中间:到了时正站在自己垫的块上,撤回时先走回岸上再挖,两块都撤掉,人没掉下去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 1500)
    public static void steps_off_its_own_blocks_before_taking_them_back(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(11, 5, 5)), NATURAL.edit().takeBack(true).build()).within(1400).arrives()
                .takesBack(RouteSpec.defaults(), r -> {
                    if (r.teardown.taken().size() != 2 || !r.teardown.left().isEmpty()) {
                        throw new GameTestAssertException("应当撤掉桥上两块:" + r.teardown);
                    }
                    if (r.body.getY() - t.origin.getY() < 4.9) {
                        throw new GameTestAssertException("掉进沟里了:脚在 " + (r.body.getY() - t.origin.getY()));
                    }
                });
    }
}
