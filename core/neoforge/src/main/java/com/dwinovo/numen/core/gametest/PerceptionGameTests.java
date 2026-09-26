package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 感知:{@code inspect_block}、{@code inspect_block_storage}、{@code look_around}、{@code scan_nearby_entities}、
 * {@code get_self_status}、{@code get_world_info}、{@code get_owner_status}、{@code lookup_recipe}、
 * {@code scaffold_materials}。这些工具不动世界,测的是回执说的是不是眼前的真事。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class PerceptionGameTests {

    /** 感知批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_perception")
    public static void preparePerceptionBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 看一格方块:是什么、够不够得着。手边那块石头够得着,场地另一头那块够不着。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_names_the_block_and_its_reach(GameTestHelper helper) {
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 3));
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        helper.getLevel().setBlockAndUpdate(near, Blocks.STONE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(far, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_inspector", new BlockPos(3, 2, 3), false);
        ToolRun atNear = call(companion, "inspect_block", args("x", near.getX(), "y", near.getY(), "z", near.getZ()));
        ToolRun atFar = call(companion, "inspect_block", args("x", far.getX(), "y", far.getY(), "z", far.getZ()));

        helper.succeedWhen(() -> {
            JsonObject n = json(atNear);
            JsonObject f = json(atFar);
            helper.assertTrue(n.get("block").getAsString().equals("minecraft:stone") && n.get("in_reach").getAsBoolean(),
                    "the stone beside her is not reported as stone within reach: " + atNear.reply());
            helper.assertTrue(!f.get("in_reach").getAsBoolean(),
                    "the stone across the site is reported within reach: " + atFar.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 不开界面读箱子里有什么:五颗钻石,报出来就是五颗钻石。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_storage_reads_a_chest_without_opening_it(GameTestHelper helper) {
        BlockPos chest = chestWithDiamonds(helper, new BlockPos(5, 2, 3), 5);
        NumenPlayer companion = spawnAt(helper, "gametest_auditor", new BlockPos(3, 2, 3), false);
        ToolRun storage = call(companion, "inspect_block_storage",
                args("x", chest.getX(), "y", chest.getY(), "z", chest.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(storage.succeeded() && storage.reply().contains("diamond")
                            && storage.reply().contains("5"),
                    "the chest's diamonds are not in the reply: " + storage.reply());
            helper.assertTrue(companion.containerMenu == companion.inventoryMenu, "reading the chest opened it");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 俯视图:以她为中心,东边两格的墙是 #,北边两格的台阶是 ^,西边两格的水是 ~。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void look_around_draws_walls_steps_and_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, 2, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(10, 3, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(8, 2, 6)), Blocks.STONE.defaultBlockState());
        // 地板只有一层,底下是空的:先垫一块,水才不会漏下去
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(6, 0, 8)), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(new BlockPos(6, 1, 8)), Blocks.WATER.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_surveyor", new BlockPos(8, 2, 8), false);

        java.util.concurrent.atomic.AtomicReference<ToolRun> map = new java.util.concurrent.atomic.AtomicReference<>();
        // 图以她脚下那一格为中心,落地之前那一格还没定
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(companion.onGround(), "she has not landed"))
                .thenExecute(() -> map.set(call(companion, "look_around", args())))
                .thenWaitUntil(() -> {
                    String m = map.get().reply();
                    helper.assertTrue(cell(m, 2, 0) == '#', "the wall two east is not #: \n" + m);
                    helper.assertTrue(cell(m, 0, -2) == '^', "the step two north is not ^: \n" + m);
                    helper.assertTrue(cell(m, -2, 0) == '~', "the water two west is not ~: \n" + m);
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /** 周围的实体:一头猪列在里面,带着能交给 attack 的 id;只看敌对的时候它不在。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void scan_nearby_entities_lists_a_pig_and_filters_by_kind(GameTestHelper helper) {
        Pig pig = EntityType.PIG.create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 3));
        pig.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        NumenPlayer companion = spawnAt(helper, "gametest_watcher", new BlockPos(3, 2, 3), false);
        ToolRun all = call(companion, "scan_nearby_entities", args("radius", 12, "type_filter", "all"));
        ToolRun hostile = call(companion, "scan_nearby_entities", args("radius", 12, "type_filter", "hostile"));

        helper.succeedWhen(() -> {
            helper.assertTrue(all.reply().contains("\"id\":" + pig.getId()) && all.reply().contains("pig"),
                    "the pig is not listed with its id: " + all.reply());
            helper.assertTrue(!hostile.reply().contains("\"id\":" + pig.getId()),
                    "the pig is listed as hostile: " + hostile.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 她自己的状态:手里的剑、背包用了几格、血与饥饿都照实报。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_self_status_reports_what_she_carries(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_selfie", new BlockPos(3, 2, 3), false);
        companion.getInventory().add(new ItemStack(Items.IRON_SWORD));
        companion.getInventory().add(new ItemStack(Items.DIAMOND, 3));
        ToolRun status = call(companion, "get_self_status", args());
        ToolRun viaCommand = command(companion, "status self");

        helper.succeedWhen(() -> {
            JsonObject s = json(status);
            helper.assertTrue(s.get("equipment").toString().contains("minecraft:iron_sword"),
                    "the sword in her hand is not reported: " + status.reply());
            helper.assertTrue(s.getAsJsonObject("backpack_slots").get("used").getAsInt() == 2,
                    "the backpack does not count two used slots: " + status.reply());
            helper.assertTrue(s.get("hp").getAsFloat() == companion.getHealth(), "hp is not her health");
            helper.assertTrue(status.reply().equals(viaCommand.reply()),
                    "get_self_status and status self read differently: " + status.reply() + " / "
                            + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 世界信息:主世界、白天、晴天。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_world_info_tells_day_and_clear_weather(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_skywatcher", new BlockPos(3, 2, 3), false);
        ToolRun info = command(companion, "status world");

        helper.succeedWhen(() -> {
            JsonObject w = json(info);
            helper.assertTrue(w.get("dimension").getAsString().equals("minecraft:overworld")
                            && w.get("is_bright_outside").getAsBoolean()
                            && w.get("weather").getAsString().equals("clear"),
                    "the world info is not overworld, day and clear: " + info.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 主人不在线就说不在线;主人在场就报名字和离她多远。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void get_owner_status_reports_whether_the_owner_is_here(GameTestHelper helper) {
        NumenPlayer alone = spawnAt(helper, "gametest_orphan", new BlockPos(3, 2, 3), false);
        NumenPlayer companion = spawnAt(helper, "gametest_ward", new BlockPos(3, 2, 8), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_guardian");
        ToolRun absent = call(alone, "get_owner_status", args());
        ToolRun present = call(companion, "get_owner_status", args());
        ToolRun viaCommand = command(companion, "status owner");

        helper.succeedWhen(() -> {
            helper.assertTrue(!json(absent).get("online").getAsBoolean(),
                    "an absent owner is reported online: " + absent.reply());
            JsonObject p = json(present);
            helper.assertTrue(p.get("online").getAsBoolean() && p.get("name").getAsString().equals("gametest_guardian")
                            && p.has("distance_to_me"),
                    "the present owner is not reported with name and distance: " + present.reply());
            helper.assertTrue(present.reply().equals(viaCommand.reply()),
                    "get_owner_status and status owner read differently: " + present.reply() + " / "
                            + viaCommand.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), alone);
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
            CompanionFactory.despawn(helper.getLevel().getServer(), owner);
        });
    }

    /** 查配方:铁锭既能炼(熔炉)也能合(铁块、铁粒),两种都列出来并标明在哪做。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_lists_every_station(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_scholar", new BlockPos(3, 2, 3), false);
        ToolRun recipe = call(companion, "lookup_recipe", args("item_id", "minecraft:iron_ingot"));

        helper.succeedWhen(() -> {
            helper.assertTrue(recipe.succeeded() && recipe.reply().contains("[smelting")
                            && recipe.reply().contains("[crafting]"),
                    "iron ingot's smelting and crafting recipes are not both listed: " + recipe.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 垫脚材料是她自己的长期选择,跟着同伴名册落盘:加上木板就列在里面,删掉出厂就有的安山岩就不在了。
     * 用和召唤同一条路生成的同伴——名册里没有她,清单就无处可存。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void scaffold_materials_adds_and_deletes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer companion = com.dwinovo.numen.entity.Companions.summon(level.getServer(), java.util.UUID.randomUUID(),
                "gametest_mason", level, new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        ToolRun added = call(companion, "scaffold_materials",
                args("action", "add", "block_ids", List.of("minecraft:oak_planks")));
        ToolRun deleted = call(companion, "scaffold_materials",
                args("action", "delete", "block_ids", List.of("minecraft:andesite")));
        ToolRun now = call(companion, "scaffold_materials", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(added.succeeded() && deleted.succeeded(), "add or delete failed: " + added.reply()
                    + " / " + deleted.reply());
            helper.assertTrue(now.reply().contains("minecraft:oak_planks") && !now.reply().contains("minecraft:andesite"),
                    "the list does not hold planks without andesite: " + now.reply());
            com.dwinovo.numen.entity.Companions.dismiss(level.getServer(), companion);
        });
    }

    private static JsonObject json(ToolRun run) {
        return JsonParser.parseString(run.reply()).getAsJsonObject();
    }

    /**
     * 俯视图里离她 {@code (dx, dz)} 的那一格:东为 +x、南为 +z。图的每一行格子之间隔一个空格,{@code @} 所在的
     * 那一行、那一列就是她。
     */
    private static char cell(String map, int dx, int dz) {
        List<String> rows = map.lines().filter(l -> !l.isEmpty() && l.charAt(1) == ' '
                && !l.startsWith("look_around") && !l.startsWith("legend")).toList();
        int row = -1;
        int col = -1;
        for (int r = 0; r < rows.size(); r++) {
            int c = rows.get(r).indexOf('@');
            if (c >= 0) {
                row = r;
                col = c;
            }
        }
        return rows.get(row + dz).charAt(col + 2 * dx);
    }

    /** 读一块石头和一格空气的存储:石头如实说没有存储;空气直接失败,说那儿什么都没有。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void inspect_block_storage_on_stone_and_on_air(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 3));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        BlockPos air = helper.absolutePos(new BlockPos(5, 3, 3));
        NumenPlayer companion = spawnAt(helper, "gametest_prober", new BlockPos(3, 2, 3), false);
        ToolRun onStone = call(companion, "inspect_block_storage", args("x", stone.getX(), "y", stone.getY(), "z", stone.getZ()));
        ToolRun onAir = call(companion, "inspect_block_storage", args("x", air.getX(), "y", air.getY(), "z", air.getZ()));

        helper.succeedWhen(() -> {
            helper.assertTrue(onStone.succeeded() && onStone.reply().contains("exposes no item/fluid/energy storage"),
                    "stone was not reported as holding nothing: " + onStone.reply());
            helper.assertTrue(!onAir.succeeded() && onAir.reply().contains("is air"),
                    "air was not reported as nothing to read: " + onAir.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 查一样合成、烧炼都做不出来的东西(末影珍珠):回执说没有配方,要靠别的途径得到。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_for_something_not_made_says_so(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_curious", new BlockPos(3, 2, 3), false);
        ToolRun recipe = call(companion, "lookup_recipe", args("item_id", "minecraft:ender_pearl"));

        helper.succeedWhen(() -> {
            helper.assertTrue(recipe.succeeded() && recipe.reply().contains("no recipe for ender_pearl"),
                    "the reply does not say ender pearls have no recipe: " + recipe.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 查一个不存在的物品 id:按参数错误退回,说出是哪个 id。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_perception")
    public static void lookup_recipe_for_an_unknown_item_is_rejected(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_misspeller", new BlockPos(3, 2, 3), false);
        ToolRun recipe = call(companion, "lookup_recipe", args("item_id", "minecraft:no_such_item"));

        helper.succeedWhen(() -> {
            helper.assertTrue(!recipe.succeeded() && recipe.reply().contains("invalid arguments")
                            && recipe.reply().contains("no_such_item"),
                    "the unknown id was not rejected by name: " + recipe.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 找方块的搜索按工作量收工,和每刻分到多少时间无关:每一刻都先让别的搜索把整刻的时间上限吃光,她这次
     * 查询照样在有限的刻数里回来,带着结论(看满节数上限而截断,或走完)——不会因为机器慢、别人忙而永远挂着。
     * mine"图回来之前不下够不着的结论"靠的就是这一条:图一定会回来。
     *
     * <p>单独一个批次:这条用例每刻都把共享的时间上限耗尽,和别的用例同批会拖慢它们的搜索。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_scan_budget")
    public static void a_block_search_returns_even_when_every_tick_is_already_spent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sponge = helper.absolutePos(new BlockPos(12, 2, 12));
        level.setBlockAndUpdate(sponge, Blocks.SPONGE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_starved", new BlockPos(3, 2, 3), false);
        java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.core.scan.BlockSearch.ScanResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 测试刻在服务端刻里先于刻末的找方块推进:这里先把本刻的时间上限整个耗掉
        helper.onEachTick(() -> {
            if (result.get() != null) {
                return;
            }
            try (com.dwinovo.numen.core.scan.SearchBudget.Slice hog =
                         com.dwinovo.numen.core.scan.SearchBudget.slice(level.getServer())) {
                while (com.dwinovo.numen.core.scan.SearchBudget.withinTime()) {
                    Thread.onSpinWait();
                }
            }
        });
        com.dwinovo.numen.core.scan.BlockSearch.start(companion.getUUID(), level, companion.blockPosition(), 24,
                com.dwinovo.numen.core.scan.BlockSearch.MAX_COLLECT, java.util.Set.of(Blocks.SPONGE), result::set);

        helper.succeedWhen(() -> {
            var res = result.get();
            helper.assertTrue(res != null, "the search never came back while every tick was already spent");
            helper.assertTrue(res.matches().stream().anyMatch(h -> h.pos().equals(sponge)),
                    "the sponge on the site was not found");
            helper.assertTrue(res.sectionCapHit() || !res.stoppedEarly() && !res.collectCapHit(),
                    "the search came back without a conclusion about its coverage");
            level.removeBlock(sponge, false);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
