package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.plugins.tlm.MaidLook;
import com.dwinovo.numen.plugins.tlm.Outfit;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.info.ServerCustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.misc.MonsterType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.PickType;
import com.github.tartaricacid.touhoulittlemaid.init.InitTaskData;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.SlotItemHandler;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 车万女仆在场时她养女仆:驯服、名下清单、切工作模式、改日程、开背包页放东西、够不着、别人的女仆、女仆死了、女仆喂她。
 * 都从入口调(`use entity`、`tlm …` 这几行命令),女仆用代码生成。
 *
 * <p>只在挂着车万女仆的那一次跑批里跑({@code :plugins:tlm:runGameTestServer},见插件的 build.gradle),命名空间
 * {@value #NAMESPACE};core 那一次跑批里没有这些用例,也没有车万女仆。
 *
 * <p>权限层照出厂规则:对她自己的女仆动手由 {@code use_entity(self_owned)} 放行,野生女仆由 {@code use_entity(!owned)}
 * 放行,都不问。只有别人的女仆那一条开 {@link Mode#BYPASS}:别人的女仆出厂规则一行都没说到,照旧要问主人,用例里的主人
 * 不在线,一问就按拒绝收场;那一条测的是车万女仆自己的主人判据,得先让权限层放过去。
 */
@GameTestHolder(TlmGameTests.NAMESPACE)
@PrefixGameTestTemplate(false)
public class TlmGameTests {

    static final String NAMESPACE = "numen_tlm";
    private static final String BATCH = "numen_tlm";
    private static final ResourceLocation FARM = ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "farm");

    @BeforeBatch(batch = BATCH)
    public static void prepareTlmBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 野生女仆拿蛋糕驯服:她归了她,{@code tlm maids} 列出她,驯服的事件进了出箱。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void a_wild_maid_tamed_with_cake_is_listed_as_hers(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_tamer", new BlockPos(3, 2, 3));
        her.getInventory().add(new ItemStack(Items.CAKE));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 4));
        ToolRun tame = lua(her, "numen.use.entity(" + maid.getId() + ", {item = \"minecraft:cake\"})");
        AtomicReference<ToolRun> listed = new AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(maid.isOwnedBy(her),
                        "the cake did not tame her — use entity said: " + tame.outcome()))
                .thenExecute(() -> listed.set(lua(her, "tlm.maid.list()")))
                .thenWaitUntil(() -> {
                    String reply = listed.get().reply();
                    helper.assertTrue(reply != null && listed.get().succeeded()
                            && hasRow(reply, maid.getId()), "tlm maids leaves her out: " + reply);
                    helper.assertTrue(outboxHas(her, "maid_tamed", maid.getId()),
                            "no maid_tamed event: " + outbox(her).peek(her.getUUID()).entries());
                })
                .thenExecute(() -> leave(helper, her, maid))
                .thenSucceed();
    }

    /**
     * 切工作模式与改日程:{@code tlm task} 切到种地、读回来就是种地;{@code tlm config} 把日程改成夜班。{@code tlm maid}
     * 列出每个工作模式。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void her_maid_switches_to_farming_and_to_the_night_shift(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_farmer", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);

        ToolRun detail = lua(her, "tlm.maid.info(" + maid.getId() + ")");
        ToolRun task = lua(her, "tlm.maid.task(\"" + FARM + "\", {maid = " + maid.getId() + "})");
        ToolRun config = lua(her, "tlm.maid.config(" + maid.getId() + ", {schedule = \"night\"})");

        succeedWhen(helper, () -> {
            helper.assertTrue(detail.succeeded() && detail.reply().contains("\"task\":\"" + FARM + "\""),
                    "tlm maid does not list the farm work mode: " + detail.reply());
            helper.assertTrue(task.succeeded() && maid.getTask().getUid().equals(FARM),
                    "she is not farming — tlm task said: " + task.reply());
            helper.assertTrue(config.succeeded() && maid.getSchedule() == MaidSchedule.NIGHT,
                    "she is not on the night shift — tlm config said: " + config.reply());
            leave(helper, her, maid);
        });
    }

    /** 「女仆配置」页的八样一次改完,女仆身上的设置就是改成的样子,回执读回的也是。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void her_maid_takes_every_setting_of_the_config_page(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_configurer", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);

        ToolRun config = lua(her, "tlm.maid.config(" + maid.getId() + ", {show_backpack = false, "
                + "show_back_item = false, chat_bubble = false, sound_frequency = 0.3, pickup_kind = \"xp\", "
                + "open_door = false, open_fence_gate = false, active_climbing = false})");

        succeedWhen(helper, () -> {
            helper.assertTrue(config.succeeded(), "tlm maid config failed: " + config.reply());
            var manager = maid.getConfigManager();
            helper.assertTrue(!manager.isShowBackpack() && !manager.isShowBackItem() && !manager.isChatBubbleShow()
                            && Math.abs(manager.getSoundFreq() - 0.3f) < 0.001f
                            && manager.getPickupType() == PickType.ONLY_XP && !manager.isOpenDoor()
                            && !manager.isOpenFenceGate() && !manager.isActiveClimbing(),
                    "the config page did not take: " + config.reply());
            var read = dataIn(config.reply()).getAsJsonObject("preferences");
            helper.assertTrue("xp".equals(read.get("pickup_kind").getAsString()) && !read.get("open_door").getAsBoolean(),
                    "the receipt does not read the settings back: " + config.reply());
            leave(helper, her, maid);
        });
    }

    /** 详情里有装备、药水效果、经验、无敌、此刻在做的事,都是女仆身上此刻的样子。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void her_maid_info_reads_gear_effects_and_activity(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_reader", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);
        maid.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        maid.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 1));
        maid.setExperience(42);
        maid.setEntityInvulnerable(true);

        ToolRun info = lua(her, "tlm.maid.info(" + maid.getId() + ")");

        succeedWhen(helper, () -> {
            helper.assertTrue(info.succeeded(), "tlm maid info failed: " + info.reply());
            var detail = dataIn(info.reply());
            var equipment = detail.getAsJsonObject("equipment");
            helper.assertTrue("minecraft:iron_helmet".equals(equipment.getAsJsonObject("head").get("item").getAsString())
                            && "minecraft:iron_sword".equals(equipment.getAsJsonObject("mainhand").get("item").getAsString())
                            && !equipment.has("feet"),
                    "info does not list what she wears and holds: " + info.reply());
            var effect = detail.getAsJsonArray("effects").get(0).getAsJsonObject();
            helper.assertTrue("minecraft:regeneration".equals(effect.get("effect").getAsString())
                            && effect.get("amplifier").getAsInt() == 1,
                    "info does not list her effect: " + info.reply());
            helper.assertTrue(detail.get("experience").getAsInt() == 42 && detail.get("invulnerable").getAsBoolean()
                            && detail.get("activity").getAsString().startsWith("minecraft:")
                            && !detail.get("sleeping").getAsBoolean(),
                    "info does not read experience, invulnerability and activity: " + info.reply());
            leave(helper, her, maid);
        });
    }

    /** 攻击名单:改一条、读回来、再去掉;名单就是女仆身上的那份数据。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = BATCH)
    public static void her_maid_attack_list_is_set_read_and_cleared(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_hunter", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);
        ResourceLocation creeper = ResourceLocation.withDefaultNamespace("creeper");
        AtomicReference<ToolRun> read = new AtomicReference<>();
        AtomicReference<ToolRun> clear = new AtomicReference<>();

        ToolRun set = lua(her, "tlm.maid.set_targets(" + maid.getId() + ", {set = {[\"minecraft:creeper\"] = \"friendly\"}})");
        steps(helper)
                .thenWaitUntil(() -> {
                    helper.assertTrue(set.succeeded(), "tlm maid targets set failed: " + set.reply());
                    var data = maid.getData(InitTaskData.ATTACK_LIST);
                    helper.assertTrue(data != null && data.attackGroups().get(creeper) == MonsterType.FRIENDLY,
                            "the attack list on her has no friendly creeper: " + set.reply());
                })
                .thenExecute(() -> read.set(lua(her, "tlm.maid.targets(" + maid.getId() + ")")))
                .thenWaitUntil(() -> helper.assertTrue(read.get().succeeded()
                                && "friendly".equals(dataIn(read.get().reply()).getAsJsonObject("stances")
                                .get("minecraft:creeper").getAsString()),
                        "targets does not read the list: " + read.get().reply()))
                .thenExecute(() -> clear.set(lua(her, "tlm.maid.set_targets(" + maid.getId()
                        + ", {remove = {\"minecraft:creeper\"}})")))
                .thenWaitUntil(() -> helper.assertTrue(clear.get().succeeded()
                                && maid.getData(InitTaskData.ATTACK_LIST).attackGroups().isEmpty(),
                        "the creeper stayed on her attack list: " + clear.get().reply()))
                .thenExecute(() -> leave(helper, her, maid))
                .thenSucceed();
    }

    /** 拿着名牌给女仆改名:名字和常显都照给的,名牌被用掉一块,回执说明。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void her_maid_is_named_with_a_name_tag(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_namer", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);
        her.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NAME_TAG, 2));

        ToolRun name = lua(her, "tlm.maid.name(" + maid.getId() + ", \"Reimu\", {always_show = true})");

        succeedWhen(helper, () -> {
            helper.assertTrue(name.succeeded(), "tlm maid name failed: " + name.reply());
            helper.assertTrue(maid.hasCustomName() && "Reimu".equals(maid.getCustomName().getString())
                            && maid.isCustomNameVisible(), "she was not named: " + name.reply());
            helper.assertTrue(her.getMainHandItem().getCount() == 1
                            && dataIn(name.reply()).get("name_tag_used").getAsBoolean(),
                    "the name tag was not used up or not reported: " + name.reply());
            leave(helper, her, maid);
        });
    }

    /** 换模型:读到她穿的,换成服务器装着的另一个,她身上就是那个。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void her_maid_model_is_read_and_changed(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_tailor", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);
        String other = ServerCustomPackLoader.SERVER_MAID_MODELS.getModelIdSet().stream()
                .filter(id -> !id.equals(maid.getModelId())).findFirst().orElseThrow();

        ToolRun read = lua(her, "tlm.maid.model(" + maid.getId() + ")");
        AtomicReference<ToolRun> change = new AtomicReference<>();
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(read.succeeded()
                                && maid.getModelId().equals(dataIn(read.reply()).get("model").getAsString()),
                        "tlm maid model does not read her model: " + read.reply()))
                .thenExecute(() -> change.set(lua(her, "tlm.maid.set_model(" + maid.getId() + ", \"" + other + "\")")))
                .thenWaitUntil(() -> helper.assertTrue(change.get().succeeded() && other.equals(maid.getModelId()),
                        "her model did not change to " + other + ": " + change.get().reply()))
                .thenExecute(() -> leave(helper, her, maid))
                .thenSucceed();
    }

    /**
     * 她自己穿女仆模型:穿上,身体上记的就是它;脱下,记的就清空。穿的是服务端登记的某个模型,服务端没有的 id 当场说没有,
     * 并给出照抄就能去搜的那一行,身上的不变。渲染要真机看,这里只验身体上的事实(同步给客户端的就是它)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void she_wears_a_maid_model_and_takes_it_off(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_model", new BlockPos(3, 2, 3));
        String model = ServerCustomPackLoader.SERVER_MAID_MODELS.getModelIdSet().stream().findFirst().orElseThrow();
        AtomicReference<ToolRun> missing = new AtomicReference<>();
        AtomicReference<ToolRun> off = new AtomicReference<>();

        ToolRun wear = lua(her, "tlm.skin.wear(\"" + model + "\")");
        steps(helper)
                .thenWaitUntil(() -> {
                    helper.assertTrue(wear.succeeded() && model.equals(Outfit.worn(her)),
                            "she does not wear " + model + ": " + wear.reply());
                    helper.assertTrue(MaidLook.describe(her).startsWith("<maid_look>"),
                            "what she wears is not in her prompt: " + MaidLook.describe(her));
                })
                .thenExecute(() -> missing.set(lua(her, "tlm.skin.wear(\"touhou_little_maid:no_such_model\")")))
                .thenWaitUntil(() -> {
                    ToolRun run = missing.get();
                    helper.assertTrue(run.refused() && "not_found".equals(run.kind())
                                    && run.hint() != null && run.hint().startsWith("tlm.skin.list("),
                            "an unknown model was not refused with the search to copy: " + run.reply());
                    helper.assertTrue(model.equals(Outfit.worn(her)), "the refused call changed what she wears");
                })
                .thenExecute(() -> off.set(lua(her, "tlm.skin.remove()")))
                .thenWaitUntil(() -> {
                    helper.assertTrue(off.get().succeeded() && Outfit.worn(her) == null,
                            "she still wears a model after remove: " + off.get().reply());
                    helper.assertTrue(MaidLook.describe(her).isEmpty(), "her prompt still says she wears a model");
                })
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), her))
                .thenSucceed();
    }

    /** 穿着的模型跟着身体走:休眠存盘、再按名册重建之后,新身体上还是那一套。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void the_model_she_wears_survives_a_save_and_rebuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos spawn = helper.absolutePos(new BlockPos(4, 2, 4));
        NumenPlayer first = Companions.summon(server, UUID.randomUUID(), "gametest_tlm_wardrobe", level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        UUID uuid = first.getUUID();
        String model = ServerCustomPackLoader.SERVER_MAID_MODELS.getModelIdSet().stream().findFirst().orElseThrow();

        ToolRun wear = lua(first, "tlm.skin.wear(\"" + model + "\")");
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(wear.succeeded(), "tlm.skin.wear failed: " + wear.reply()))
                .thenExecute(() -> {
                    Companions.dormant(server, first);
                    Companions.respawn(server, uuid);
                })
                .thenWaitUntil(() -> {
                    NumenPlayer live = NumenPlayer.findByUuid(server, uuid);
                    helper.assertTrue(live != null, "the body did not come back");
                    helper.assertTrue(model.equals(Outfit.worn(live)),
                            "the model she wore did not come back with her: " + Outfit.worn(live));
                    Companions.dismiss(server, live);
                })
                .thenSucceed();
    }

    /** 祭坛配方清单:有让女仆复活的那条,带材料和 P 点。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void the_altar_lists_its_recipes(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_priest", new BlockPos(3, 2, 3));

        ToolRun recipes = lua(her, "tlm.altar.recipes()");

        succeedWhen(helper, () -> {
            helper.assertTrue(recipes.succeeded(), "tlm altar recipes failed: " + recipes.reply());
            boolean revive = false;
            for (JsonElement row : valueIn(recipes.reply()).getAsJsonArray()) {
                var recipe = row.getAsJsonObject();
                revive |= recipe.has("spawns") && "touhou_little_maid:maid".equals(recipe.get("spawns").getAsString())
                        && recipe.getAsJsonArray("needs").size() > 0 && recipe.get("power").getAsDouble() > 0;
            }
            helper.assertTrue(revive, "the altar's maid revival recipe is not listed: " + recipes.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 开背包页,再用 {@code gui move} 把她背包里的一把种子放进女仆自己的第一格。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void the_backpack_page_takes_seeds_by_use_transfer(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_packer", new BlockPos(3, 2, 3));
        her.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 5));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);

        ToolRun open = lua(her, "tlm.maid.open(" + maid.getId() + ", {tab = \"backpack\"})");
        AtomicReference<ToolRun> moved = new AtomicReference<>();
        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(open.succeeded()
                                && her.containerMenu instanceof AbstractMaidContainer menu && menu.getMaid() == maid,
                        "her backpack page is not open — tlm open said: " + open.reply()))
                .thenExecute(() -> {
                    int from = -1;
                    int to = -1;
                    for (int i = 0; i < her.containerMenu.slots.size(); i++) {
                        Slot slot = her.containerMenu.slots.get(i);
                        if (from < 0 && slot.container == her.getInventory() && slot.getItem().is(Items.WHEAT_SEEDS)) {
                            from = i;
                        }
                        if (to < 0 && slot instanceof SlotItemHandler own && own.getItemHandler() == maid.getMaidInv()
                                && own.getSlotIndex() == 0) {
                            to = i;
                        }
                    }
                    helper.assertTrue(from >= 0 && to >= 0, "no seed slot or no maid slot 0 in the open GUI");
                    moved.set(lua(her, "numen.gui.move(" + from + ", " + to + ")"));
                })
                .thenWaitUntil(() -> helper.assertTrue(maid.getMaidInv().getStackInSlot(0).is(Items.WHEAT_SEEDS),
                        "the seeds did not go into her slot — gui move said: "
                                + (moved.get() == null ? null : moved.get().outcome())))
                .thenExecute(() -> leave(helper, her, maid))
                .thenSucceed();
    }

    /** 离得太远:{@code tlm task} 不走路,当场失败,给出照抄就能走过去的那一行,女仆的工作没动。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void too_far_from_her_maid_names_the_walk(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_distant", new BlockPos(1, 2, 1));
        EntityMaid maid = maidAt(helper, new BlockPos(14, 2, 14));
        maid.tame(her);
        // 坐着:不跟过来,也不传送到她身边
        maid.setInSittingPose(true);

        ToolRun task = lua(her, "tlm.maid.task(\"" + FARM + "\", {maid = " + maid.getId() + "})");

        succeedWhen(helper, () -> {
            String hint = task.hint();
            helper.assertTrue(task.refused() && "out_of_reach".equals(task.kind())
                            && hint != null && hint.startsWith("numen.move.to({x = ")
                            && hint.endsWith("{arrive = \"near\", range = 2})"),
                    "far away, tlm task did not fail out of reach with the walk to copy: " + task.reply());
            helper.assertTrue(!maid.getTask().getUid().equals(FARM), "her task changed from out of reach");
            leave(helper, her, maid);
        });
    }

    /** 别人的女仆:权限层放行了,车万女仆自己的主人判据不照做;回执说她不是她的,工作没动。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void someone_elses_maid_keeps_her_task(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_meddler", new BlockPos(3, 2, 3));
        Permission.setMode(her, Mode.BYPASS);
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.setTame(true, false);
        maid.setOwnerUUID(UUID.randomUUID());

        ToolRun task = lua(her, "tlm.maid.task(\"" + FARM + "\", {maid = " + maid.getId() + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(task.reply() != null && !task.succeeded() && task.reply().contains("not yours"),
                    "tlm task on someone else's maid did not fail by TLM's owner rule: " + task.reply());
            helper.assertTrue(!maid.getTask().getUid().equals(FARM), "someone else's maid took her order");
            leave(helper, her, maid);
        });
    }

    /** 她的女仆死了:急件进出箱,带着墓碑的编号;{@code tlm maids} 列出那块墓碑。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = BATCH)
    public static void her_maid_dying_reports_the_tombstone(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_mourner", new BlockPos(3, 2, 3));
        EntityMaid maid = maidAt(helper, new BlockPos(5, 2, 3));
        maid.tame(her);
        int id = maid.getId();
        maid.kill();

        succeedWhen(helper, () -> {
            var died = outbox(her).peek(her.getUUID()).entries().stream()
                    .filter(e -> e.type().equals("maid_died") && e.text().contains("maid=\"" + id + "\"")).toList();
            helper.assertTrue(died.size() == 1 && died.get(0).urgent() && died.get(0).text().contains("tombstone=\""),
                    "no urgent maid_died event with a tombstone: " + outbox(her).peek(her.getUUID()).entries());
            ToolRun listed = lua(her, "tlm.maid.list()");
            helper.assertTrue(listed.succeeded() && dataIn(listed.reply()).getAsJsonArray("tombstones").size() == 1,
                    "tlm maids does not list the tombstone: " + listed.reply());
            leave(helper, her, maid);
        });
    }

    /** 喂食的女仆喂了饿着的她:她的饱食度上去了,喂食的事件进了出箱。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = BATCH)
    public static void her_feeding_maid_feeds_her_and_she_is_told(GameTestHelper helper) {
        NumenPlayer her = keeper(helper, "gametest_tlm_hungry", new BlockPos(3, 2, 3));
        her.getFoodData().setFoodLevel(6);
        EntityMaid maid = maidAt(helper, new BlockPos(4, 2, 3));
        maid.tame(her);
        maid.getMaidInv().setStackInSlot(0, new ItemStack(Items.COOKED_BEEF, 4));
        ToolRun task = lua(her, "tlm.maid.task(\"touhou_little_maid:feed\", {maid = " + maid.getId() + "})");

        succeedWhen(helper, () -> {
            helper.assertTrue(task.succeeded(), "tlm task feed failed: " + task.reply());
            helper.assertTrue(outboxHas(her, "maid_fed_you", -1),
                    "no maid_fed_you event: " + outbox(her).peek(her.getUUID()).entries());
            helper.assertTrue(her.getFoodData().getFoodLevel() > 6, "her food did not go up");
            leave(helper, her, maid);
        });
    }

    // ---- 共用 ----

    /** 一只养女仆的同伴:生存模式,权限照出厂规则(见类注释)。 */
    private static NumenPlayer keeper(GameTestHelper helper, String name, BlockPos rel) {
        return spawnAt(helper, name, rel, false);
    }

    /** 在 {@code rel} 那一格上生成一只野生女仆。 */
    private static EntityMaid maidAt(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(rel);
        EntityMaid maid = EntityMaid.TYPE.create(level);
        maid.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        level.addFreshEntity(maid);
        return maid;
    }

    private static EventOutbox outbox(NumenPlayer her) {
        return EventOutbox.get(her.getServer());
    }

    /** 出箱里有这一种事件;{@code maid} 不小于 0 时还要点名这只女仆。 */
    private static boolean outboxHas(NumenPlayer her, String type, int maid) {
        return outbox(her).peek(her.getUUID()).entries().stream()
                .anyMatch(e -> e.type().equals(type) && (maid < 0 || e.text().contains("maid=\"" + maid + "\"")));
    }

    /** 清单这一页里有编号为 {@code id} 的那一行。 */
    private static boolean hasRow(String reply, int id) {
        for (JsonElement row : dataIn(reply).getAsJsonArray("here")) {
            var o = row.getAsJsonObject();
            if (o.has("id") && o.get("id").getAsInt() == id) {
                return true;
            }
        }
        return false;
    }

    /** 收场:她的出箱清掉、她离开世界、女仆收走。 */
    private static void leave(GameTestHelper helper, NumenPlayer her, EntityMaid maid) {
        outbox(her).forget(her.getUUID());
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        maid.discard();
    }
}
