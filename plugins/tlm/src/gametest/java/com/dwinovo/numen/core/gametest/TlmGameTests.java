package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.AbstractMaidContainer;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
