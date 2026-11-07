package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.api.entity.BodyAction;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 垫路料:创造模式凭空取料、按清单变料;副手回退;搭桥后手上的武器还在。
 *
 * <p>场景都是两块基岩台子中间一道四格宽的沟(沟挖不动、跳下去摔不起),过沟只能搭桥。
 */
public class MaterialGameTests {

    private static final String BATCH = "pathing_materials";

    private static final RouteSpec NATURAL = RouteSpec.defaults().edit().changes(true).consent(false).build();

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
                    throw Checks.failure("应当放 " + block + ",却放了 " + p.after() + " 在 " + p.pos());
                }
            }
        }
        if (!any) {
            throw Checks.failure("一块也没放");
        }
    }

    /** 创造模式、背包空着:照料清单的第一种凭空取一叠搭桥,身体动作里记下取料。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 423)
    public static void conjures_materials_in_creative_by_the_list(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.setGameMode(GameType.CREATIVE);
        // 这条量的是凭空取料搭桥,不是飞过去:创造模式的身体许飞,飞比搭桥便宜,所以关掉飞行的能力
        body.getAbilities().mayfly = false;
        t.materials = Trial.carried(body, Blocks.OAK_PLANKS, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(413).arrives().then(r -> {
            placedAre(r, Blocks.OAK_PLANKS);
            boolean conjured = r.report.actions().stream().anyMatch(a -> a instanceof BodyAction.Conjured c
                    && c.item() == Items.OAK_PLANKS);
            if (!conjured) {
                throw Checks.failure("凭空取料没记进身体动作:" + r.report.actions());
            }
        });
    }

    /** 料只在副手,主手拿着剑:主副手一换就垫,垫完剑还在身上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 423)
    public static void falls_back_to_the_offhand(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        body.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        body.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(413).arrives().then(r -> {
            placedAre(r, Blocks.COBBLESTONE);
            stillHasSword(r);
        });
    }

    /** 手上拿着剑,圆石在背包深处:中键把圆石换进快捷栏搭桥,剑换回背包,一直在身上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 423)
    public static void keeps_the_weapon_after_bridging(GameTestHelper helper) {
        Trial t = ditch(helper);
        TestBody body = t.body(6, 5, 5);
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            body.getInventory().setItem(slot, new ItemStack(Items.IRON_SWORD));
        }
        body.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 16));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(17, 5, 5)), NATURAL).within(413).arrives().then(r -> {
            placedAre(r, Blocks.COBBLESTONE);
            int swords = 0;
            for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
                if (body.getInventory().getItem(i).is(Items.IRON_SWORD)) {
                    swords++;
                }
            }
            if (swords != Inventory.getSelectionSize()) {
                throw Checks.failure("剑少了:还剩 " + swords + " 把");
            }
        });
    }

    private static void stillHasSword(Trial.Run r) {
        if (!r.body.getInventory().contains(new ItemStack(Items.IRON_SWORD))) {
            throw Checks.failure("手上原来的剑不见了");
        }
    }
}
